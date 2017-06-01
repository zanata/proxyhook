package org.flanigan.proxyhook.common

/**
 * @author Sean Flanigan [sflaniga@redhat.com](mailto:sflaniga@redhat.com)
 */
// NB vertex will pass these over the wire as strings, not ordinals
enum class MessageType {
    LOGIN, SUCCESS, FAILED,
    PING, PONG,
    WEBHOOK
}
