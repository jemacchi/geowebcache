/**
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * <p>You should have received a copy of the GNU Lesser General Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * @author Gabriel Roldan, Boundless Spatial Inc, Copyright 2015
 */
package org.geowebcache.s3;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.Objects.isNull;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AccessControlList;
import com.amazonaws.services.s3.model.BucketPolicy;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.Grant;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.geotools.util.logging.Logging;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.filter.parameters.ParametersUtils;
import org.geowebcache.io.ByteArrayResource;
import org.geowebcache.io.Resource;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.locks.LockProvider;
import org.geowebcache.mime.MimeException;
import org.geowebcache.mime.MimeType;
import org.geowebcache.s3.streams.TileDeletionListenerNotifier;
import org.geowebcache.storage.BlobStore;
import org.geowebcache.storage.BlobStoreListener;
import org.geowebcache.storage.BlobStoreListenerList;
import org.geowebcache.storage.CompositeBlobStore;
import org.geowebcache.storage.StorageException;
import org.geowebcache.storage.TileObject;
import org.geowebcache.storage.TileRange;
import org.geowebcache.util.TMSKeyBuilder;

public class S3BlobStore implements BlobStore {

    static Logger log = Logging.getLogger(S3BlobStore.class.getName());

    private final BlobStoreListenerList listeners = new BlobStoreListenerList();

    private AmazonS3Client conn;

    private final TMSKeyBuilder keyBuilder;

    private String bucketName;

    private final S3Ops s3Ops;

    private CannedAccessControlList acl;

    public S3BlobStore(S3BlobStoreInfo config, TileLayerDispatcher layers, LockProvider lockProvider)
            throws StorageException {
        checkNotNull(config);
        checkNotNull(layers);

        this.bucketName = config.getBucket();
        String prefix = config.getPrefix() == null ? "" : config.getPrefix();
        this.keyBuilder = new TMSKeyBuilder(prefix, layers);
        conn = validateClient(config.buildClient(), bucketName);
        acl = config.getAccessControlList();

        this.s3Ops = new S3Ops(conn, bucketName, keyBuilder, lockProvider, listeners);

        boolean empty = !s3Ops.prefixExists(prefix);
        boolean existing = Objects.nonNull(s3Ops.getObjectMetadata(keyBuilder.storeMetadata()));

        CompositeBlobStore.checkSuitability(config.getLocation(), existing, empty);

        // TODO replace this with real metadata.  For now it's just a marker
        // to indicate this is a GWC cache.
        s3Ops.putProperties(keyBuilder.storeMetadata(), new Properties());
    }

    /**
     * Validates the client connection by running some {@link S3ClientChecker}, returns the valiated client on success,
     * otherwise throws an exception
     */
    protected AmazonS3Client validateClient(AmazonS3Client client, String bucketName) throws StorageException {
        List<S3ClientChecker> connectionCheckers = Arrays.asList(this::checkBucketPolicy, this::checkAccessControlList);
        List<Exception> exceptions = new ArrayList<>();
        for (S3ClientChecker checker : connectionCheckers) {
            try {
                checker.validate(client, bucketName);
                break;
            } catch (Exception e) {
                exceptions.add(e);
            }
        }
        if (exceptions.size() == connectionCheckers.size()) {
            String messages = exceptions.stream().map(e -> e.getMessage()).collect(Collectors.joining("\n"));
            throw new StorageException(
                    "Could not validate the connection to S3, exceptions gathered during checks:\n " + messages);
        }

        return client;
    }

    /** Implemented by lambdas testing an {@link AmazonS3Client} */
    interface S3ClientChecker {
        void validate(AmazonS3Client client, String bucketName) throws Exception;
    }

    /**
     * Checks a {@link com.amazonaws.services.s3.AmazonS3Client} by getting the ACL out of the bucket, as implemented by
     * S3, Cohesity, but not, for example, by Minio.
     */
    private void checkAccessControlList(AmazonS3Client client, String bucketName) throws Exception {
        try {
            log.fine("Checking access rights to bucket " + bucketName);
            AccessControlList bucketAcl = client.getBucketAcl(bucketName);
            List<Grant> grants = bucketAcl.getGrantsAsList();
            log.fine("Bucket " + bucketName + " permissions: " + grants);
        } catch (AmazonServiceException se) {
            throw new StorageException("Server error listing bucket ACLs: " + se.getMessage(), se);
        }
    }

    /**
     * Checks a {@link com.amazonaws.services.s3.AmazonS3Client} by getting the policy out of the bucket, as implemented
     * by S3, Minio, but not, for example, by Cohesity.
     */
    private void checkBucketPolicy(AmazonS3Client client, String bucketName) throws Exception {
        try {
            log.fine("Checking policy for bucket " + bucketName);
            BucketPolicy bucketPol = client.getBucketPolicy(bucketName);
            log.fine("Bucket " + bucketName + " policy: " + bucketPol.getPolicyText());
        } catch (AmazonServiceException se) {
            throw new StorageException("Server error getting bucket policy: " + se.getMessage(), se);
        }
    }

    @Override
    public void destroy() {
        AmazonS3Client conn = this.conn;
        this.conn = null;
        if (conn != null) {
            s3Ops.shutDown();
            conn.shutdown();
        }
    }

    @Override
    public void addListener(BlobStoreListener listener) {
        listeners.addListener(listener);
    }

    @Override
    public boolean removeListener(BlobStoreListener listener) {
        return listeners.removeListener(listener);
    }

    @Override
    public void put(TileObject obj) throws StorageException {
        final Resource blob = obj.getBlob();
        checkNotNull(blob);
        checkNotNull(obj.getBlobFormat());

        final String key = keyBuilder.forTile(obj);
        ObjectMetadata objectMetadata = new ObjectMetadata();
        objectMetadata.setContentLength(blob.getSize());

        String blobFormat = obj.getBlobFormat();
        String mimeType;
        try {
            mimeType = MimeType.createFromFormat(blobFormat).getMimeType();
        } catch (MimeException me) {
            throw new RuntimeException(me);
        }
        objectMetadata.setContentType(mimeType);

        // don't bother for the extra call if there are no listeners
        final boolean existed;
        ObjectMetadata oldObj;
        if (listeners.isEmpty()) {
            existed = false;
            oldObj = null;
        } else {
            oldObj = s3Ops.getObjectMetadata(key);
            existed = oldObj != null;
        }

        final ByteArrayInputStream input = toByteArray(blob);
        PutObjectRequest putObjectRequest =
                new PutObjectRequest(bucketName, key, input, objectMetadata).withCannedAcl(acl);

        log.finer(log.isLoggable(Level.FINER) ? ("Storing " + key) : "");
        s3Ops.putObject(putObjectRequest);

        putParametersMetadata(obj.getLayerName(), obj.getParametersId(), obj.getParameters());

        /*
         * This is important because listeners may be tracking tile existence
         */
        if (!listeners.isEmpty()) {
            if (existed) {
                long oldSize = oldObj.getContentLength();
                listeners.sendTileUpdated(obj, oldSize);
            } else {
                listeners.sendTileStored(obj);
            }
        }
    }

    private ByteArrayInputStream toByteArray(final Resource blob) throws StorageException {
        final byte[] bytes;
        if (blob instanceof ByteArrayResource) {
            bytes = ((ByteArrayResource) blob).getContents();
        } else {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream((int) blob.getSize());
                    WritableByteChannel channel = Channels.newChannel(out)) {
                blob.transferTo(channel);
                bytes = out.toByteArray();
            } catch (IOException e) {
                throw new StorageException("Error copying blob contents", e);
            }
        }
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        return input;
    }

    @Override
    public boolean get(TileObject obj) throws StorageException {
        final String key = keyBuilder.forTile(obj);
        try (S3Object object = s3Ops.getObject(key)) {
            if (object == null) {
                return false;
            }
            try (S3ObjectInputStream in = object.getObjectContent()) {
                byte[] bytes = ByteStreams.toByteArray(in);
                obj.setBlobSize(bytes.length);
                obj.setBlob(new ByteArrayResource(bytes));
                obj.setCreated(object.getObjectMetadata().getLastModified().getTime());
            }
        } catch (IOException e) {
            throw new StorageException("Error getting " + key, e);
        }
        return true;
    }

    @Override
    public boolean delete(final TileRange tileRange) throws StorageException {
        checkNotNull(tileRange, "tile range must not be null");
        checkArgument(tileRange.getZoomStart() >= 0, "zoom start must be greater or equal than zero");
        checkArgument(
                tileRange.getZoomStop() >= tileRange.getZoomStart(),
                "zoom stop must be greater or equal than start zoom");

        final String coordsPrefix = keyBuilder.coordinatesPrefix(tileRange, true);
        if (!s3Ops.prefixExists(coordsPrefix)) {
            return false;
        }

        // Create a prefix for each zoom level
        long count = IntStream.range(tileRange.getZoomStart(), tileRange.getZoomStop() + 1)
                .mapToObj(level -> scheduleDeleteForZoomLevel(tileRange, level))
                .filter(Objects::nonNull)
                .count();

        // Check all ranges where scheduled
        return count == (tileRange.getZoomStop() - tileRange.getZoomStart() + 1);
    }

    private String scheduleDeleteForZoomLevel(TileRange tileRange, int level) {
        String zoomPath = keyBuilder.forZoomLevel(tileRange, level);
        Bounds bounds = new Bounds(tileRange.rangeBounds(level));
        String prefix = format("%s?%s", zoomPath, bounds);
        try {
            s3Ops.scheduleAsyncDelete(prefix);
            return prefix;
        } catch (GeoWebCacheException e) {
            log.warning("Cannot schedule delete for prefix " + prefix);
            return null;
        }
    }

    @Override
    public boolean delete(String layerName) throws StorageException {
        checkNotNull(layerName, "layerName");

        final String metadataKey = keyBuilder.layerMetadata(layerName);
        final String layerPrefix = keyBuilder.forLayer(layerName);

        s3Ops.deleteObject(metadataKey);

        boolean layerExists;
        try {
            layerExists = s3Ops.scheduleAsyncDelete(layerPrefix);
        } catch (GeoWebCacheException e) {
            throw new RuntimeException(e);
        }
        if (layerExists) {
            listeners.sendLayerDeleted(layerName);
        }
        return layerExists;
    }

    @Override
    public boolean deleteByGridsetId(final String layerName, final String gridSetId) throws StorageException {

        checkNotNull(layerName, "layerName");
        checkNotNull(gridSetId, "gridSetId");

        final String gridsetPrefix = keyBuilder.forGridset(layerName, gridSetId);

        boolean prefixExists;
        try {
            prefixExists = s3Ops.scheduleAsyncDelete(gridsetPrefix);
        } catch (GeoWebCacheException e) {
            throw new RuntimeException(e);
        }
        if (prefixExists) {
            listeners.sendGridSubsetDeleted(layerName, gridSetId);
        }
        return prefixExists;
    }

    @Override
    public boolean delete(TileObject obj) throws StorageException {
        final String key = keyBuilder.forTile(obj);

        // don't bother for the extra call if there are no listeners
        if (listeners.isEmpty()) {
            return s3Ops.deleteObject(key);
        }

        ObjectMetadata oldObj = s3Ops.getObjectMetadata(key);

        if (oldObj == null) {
            return false;
        }

        s3Ops.deleteObject(key);
        obj.setBlobSize((int) oldObj.getContentLength());
        listeners.sendTileDeleted(obj);
        return true;
    }

    @Override
    public boolean rename(String oldLayerName, String newLayerName) throws StorageException {
        log.fine("No need to rename layers, S3BlobStore uses layer id as key root");
        if (s3Ops.prefixExists(oldLayerName)) {
            listeners.sendLayerRenamed(oldLayerName, newLayerName);
        }
        return true;
    }

    @Override
    public void clear() throws StorageException {
        throw new UnsupportedOperationException("clear() should not be called");
    }

    @Nullable
    @Override
    public String getLayerMetadata(String layerName, String key) {
        Properties properties = getLayerMetadata(layerName);
        String value = properties.getProperty(key);
        return value;
    }

    @Override
    public void putLayerMetadata(String layerName, String key, String value) {
        Properties properties = getLayerMetadata(layerName);
        properties.setProperty(key, value);
        String resourceKey = keyBuilder.layerMetadata(layerName);
        try {
            s3Ops.putProperties(resourceKey, properties);
        } catch (StorageException e) {
            throw new RuntimeException(e);
        }
    }

    private Properties getLayerMetadata(String layerName) {
        String key = keyBuilder.layerMetadata(layerName);
        return s3Ops.getProperties(key);
    }

    private void putParametersMetadata(String layerName, String parametersId, Map<String, String> parameters) {
        if (isNull(parameters)) {
            return;
        }
        Properties properties = new Properties();
        parameters.forEach(properties::setProperty);
        String resourceKey = keyBuilder.parametersMetadata(layerName, parametersId);
        try {
            s3Ops.putProperties(resourceKey, properties);
        } catch (StorageException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean layerExists(String layerName) {
        final String coordsPrefix = keyBuilder.forLayer(layerName);
        boolean layerExists = s3Ops.prefixExists(coordsPrefix);
        return layerExists;
    }

    @Override
    public boolean deleteByParametersId(String layerName, String parametersId) throws StorageException {
        checkNotNull(layerName, "layerName");
        checkNotNull(parametersId, "parametersId");

        boolean prefixExists = keyBuilder.forParameters(layerName, parametersId).stream()
                .map(prefix -> {
                    try {
                        return s3Ops.scheduleAsyncDelete(prefix);
                    } catch (RuntimeException | GeoWebCacheException e) {
                        throw new RuntimeException(e);
                    }
                })
                .reduce(Boolean::logicalOr) // Don't use Stream.anyMatch as it would short
                // circuit
                .orElse(false);
        if (prefixExists) {
            listeners.sendParametersDeleted(layerName, parametersId);
        }
        return prefixExists;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Set<Map<String, String>> getParameters(String layerName) {
        return s3Ops.objectStream(keyBuilder.parametersMetadataPrefix(layerName))
                .map(S3ObjectSummary::getKey)
                .map(s3Ops::getProperties)
                .map(props -> (Map<String, String>) (Map<?, ?>) props)
                .collect(Collectors.toSet());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Optional<Map<String, String>>> getParametersMapping(String layerName) {
        return s3Ops.objectStream(keyBuilder.parametersMetadataPrefix(layerName))
                .map(S3ObjectSummary::getKey)
                .map(s3Ops::getProperties)
                .map(props -> (Map<String, String>) (Map<?, ?>) props)
                .collect(Collectors.toMap(ParametersUtils::getId, Optional::of));
    }

    public static class Bounds {
        private static final Pattern boundsRegex =
                Pattern.compile("^(?<prefix>.*/)\\?bounds=(?<minx>\\d+),(?<miny>\\d+),(?<maxx>\\d+),(?<maxy>\\d+)$");
        private final long minX, minY, maxX, maxY;

        public Bounds(long[] bound) {
            minX = Math.min(bound[0], bound[2]);
            minY = Math.min(bound[1], bound[3]);
            maxX = Math.max(bound[0], bound[2]);
            maxY = Math.max(bound[1], bound[3]);
        }

        public long getMinX() {
            return minX;
        }

        public long getMaxX() {
            return maxX;
        }

        static Optional<Bounds> createBounds(String prefix) {
            Matcher matcher = boundsRegex.matcher(prefix);
            if (!matcher.matches()) {
                return Optional.empty();
            }

            Bounds bounds = new Bounds(new long[] {
                Long.parseLong(matcher.group("minx")),
                Long.parseLong(matcher.group("miny")),
                Long.parseLong(matcher.group("maxx")),
                Long.parseLong(matcher.group("maxy"))
            });
            return Optional.of(bounds);
        }

        static String prefixWithoutBounds(String prefix) {
            Matcher matcher = boundsRegex.matcher(prefix);
            if (matcher.matches()) {
                return matcher.group("prefix");
            }
            return prefix;
        }

        @Override
        public String toString() {
            return format("bounds=%d,%d,%d,%d", minX, minY, maxX, maxY);
        }

        public boolean predicate(S3ObjectSummary s3ObjectSummary) {
            var matcher = TileDeletionListenerNotifier.keyRegex.matcher(s3ObjectSummary.getKey());
            if (!matcher.matches()) {
                return false;
            }
            long x = Long.parseLong(matcher.group(TileDeletionListenerNotifier.X_GROUP_POS));
            long y = Long.parseLong(matcher.group(TileDeletionListenerNotifier.Y_GROUP_POS));
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }
}
