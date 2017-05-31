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

/**
 * @author Sean Flanigan <a href="mailto:sflaniga@redhat.com">sflaniga@redhat.com</a>
 */
public class Constants {
    private Constants() {
    }

    // should fit inside MAX_FRAME_SIZE after encoding
    public static final int MAX_BODY_SIZE = 6_000_000;

    // 10 megabytes. GitHub payloads are a maximum of 5MB.
    // https://developer.github.com/webhooks/#payloads
    public static final int MAX_FRAME_SIZE = 10_000_000;

    public static final String PROXYHOOK_PASSHASH = "PROXYHOOK_PASSHASH";
    public static final String PROXYHOOK_PASSWORD = "PROXYHOOK_PASSWORD";

    public static final String PATH_WEBHOOK = "webhook";
    public static final String PATH_WEBSOCKET = "listen";

}