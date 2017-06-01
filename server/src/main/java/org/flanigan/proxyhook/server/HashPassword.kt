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
package org.flanigan.proxyhook.server

import org.mindrot.jbcrypt.BCrypt

import java.lang.System.getenv
import org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSHASH
import org.flanigan.proxyhook.common.Constants.PROXYHOOK_PASSWORD

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
object HashPassword {

    @JvmStatic fun main(args: Array<String>) {
        val password = getenv(PROXYHOOK_PASSWORD)
        if (password == null) {
            System.err.println("Please set environment variable " + PROXYHOOK_PASSWORD)
            System.exit(1)
        }
        //        Console console = System.console();
        //        if (console == null) {
        //            System.err.println("No console found");
        //            System.exit(1);
        //        }
        //        String password = new String(console.readPassword("Please enter password: "));
        val hashed = BCrypt.hashpw(password, BCrypt.gensalt(12))
        println()
        println("Password hashed successfully.")
        println()
        println("You should add this environment variable to your ProxyHook client script:")
        println("export $PROXYHOOK_PASSWORD='$password'")
        println()
        println("And you should add this environment variable to your ProxyHook server script:")
        println("export $PROXYHOOK_PASSHASH='$hashed'")
    }

}
