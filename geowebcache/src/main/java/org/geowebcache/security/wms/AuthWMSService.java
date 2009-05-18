/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, The Open Planning Project, Copyright 2008
 * 
 */
package org.geowebcache.security.wms;

import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.service.wms.WMSService;
import org.geowebcache.tile.Tile;
import org.geowebcache.util.Configuration;
import org.geowebcache.util.ServletUtils;

public class AuthWMSService extends WMSService {
    private static Log log = LogFactory.getLog(AuthWMSService.class);
    
    public final static String WMS_VERSION_1_1_1 = "1.1.1";
    public final static String WMS_VERSION_1_3_0 = "1.3.0";
        
    private AuthWMSRequests wmsRequests;
    
    private DataAccessManager dataAccessManager;

    public AuthWMSService() {
        super();
    }

    @Override
    public void handleRequest(TileLayerDispatcher tLD, Tile tile)
    throws GeoWebCacheException {
        
        String[] keys = { "version" };
        String[] values = ServletUtils.selectedStringsFromMap(
                tile.servletReq.getParameterMap(), keys);
        String version;
        if (!(values == null || values.length == 0) && (WMS_VERSION_1_1_1.equals(values[0]) || WMS_VERSION_1_3_0.equals(values[0]))) {
            version = values[0];
        } else {
            version = "1.3.0";
        }

        if (tile.hint != null) {
            if(tile.hint.equalsIgnoreCase("getcapabilities")) {
                new AuthWMSRequests(dataAccessManager).handleGetCapabilities(tLD, tile, version);
            } else {
                AuthWMSRequests.handleProxy(tLD, tile);
            }
        } else {
            throw new GeoWebCacheException(
                    "The WMS Service would love to help, "
                    + "but has no idea what you're trying to do?"
                    + "Please include request URL if you file a bug report.");
        }
    }
    
    @Override
    public Tile getTile(HttpServletRequest request, HttpServletResponse response)
    throws GeoWebCacheException {
        Tile tile = super.getTile(request, response);
        if (!userCanSeeLayer(SecurityContextHolder.getContext().getAuthentication(), tile.getLayerId())) {
            throw new GeoWebCacheException("User is not authorized to see layer!");
        }

        return tile;
    }

    public void setConfig(List<Configuration> getCapConfigs) {
        AuthWMSRequests.setConfig(getCapConfigs);
        log.info("Got passed " + getCapConfigs.size() + " configuration objects for getCapabilities.");
    }

    public void setDataAccessManager(DataAccessManager dataAccessManager) {
        this.dataAccessManager = dataAccessManager;
    }

    private boolean userCanSeeLayer(Authentication user, String layerName) {
        return dataAccessManager.canAccess(user, layerName);
    }

}