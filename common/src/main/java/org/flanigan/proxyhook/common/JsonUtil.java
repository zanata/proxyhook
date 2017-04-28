/*
 * Copyright 2017, Red Hat, Inc. and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.flanigan.proxyhook.common;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.vertx.core.MultiMap;
import io.vertx.core.http.impl.HeadersAdaptor;
import io.vertx.core.json.JsonArray;

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class JsonUtil {

    private JsonUtil() {
    }

    public static JsonArray multiMapToJson(MultiMap headers) {
        JsonArray headerList = new JsonArray();
        headers.forEach(entry ->
                headerList.add(new JsonArray().add(entry.getKey()).add(entry.getValue())));
        return headerList;
    }

    public static MultiMap jsonToMultiMap(JsonArray pairs) {
        MultiMap map = new HeadersAdaptor(new DefaultHttpHeaders());
        pairs.forEach(pair -> {
            JsonArray p = (JsonArray) pair;
            map.add(p.getString(0), p.getString(1));
        });
        return map;
    }

}
