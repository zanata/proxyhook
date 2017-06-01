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
package org.flanigan.proxyhook.common

import io.netty.handler.codec.http.DefaultHttpHeaders
import io.vertx.core.MultiMap
import io.vertx.core.http.impl.HeadersAdaptor
import io.vertx.core.json.JsonArray

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
object JsonUtil {

    @JvmStatic
    fun multiMapToJson(headers: MultiMap): JsonArray {
        val headerList = JsonArray()
        headers.forEach { entry -> headerList.add(JsonArray().add(entry.key).add(entry.value)) }
        return headerList
    }

    fun jsonToMultiMap(pairs: JsonArray): MultiMap {
        val map = HeadersAdaptor(DefaultHttpHeaders())
        pairs.forEach { pair ->
            val p = pair as JsonArray
            map.add(p.getString(0), p.getString(1))
        }
        return map
    }

}
