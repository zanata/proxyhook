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

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
object Constants {

    // should fit inside MAX_FRAME_SIZE after encoding
    const val MAX_BODY_SIZE = 6_000_000

    // 10 megabytes. GitHub payloads are a maximum of 5MB.
    // https://developer.github.com/webhooks/#payloads
    const val MAX_FRAME_SIZE = 10_000_000

    const val PROXYHOOK_PASSHASH = "PROXYHOOK_PASSHASH"
    const val PROXYHOOK_PASSWORD = "PROXYHOOK_PASSWORD"

    const val PATH_WEBHOOK = "webhook"
    const val PATH_WEBSOCKET = "listen"

}
