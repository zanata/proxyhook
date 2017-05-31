package org.flanigan.proxyhook.common;

// NB vertex will pass these over the wire as strings, not ordinals
public enum MessageType {
    LOGIN, SUCCESS, FAILED,
    PING, PONG,
    WEBHOOK
}
