package com.wave.tv;

/**
 * One entry in the picker: a display name and the address it lives at.
 *
 * Deliberately mutable and deliberately without equals/hashCode. The picker
 * identifies stations by object identity precisely because two of them may
 * share a name, or an address, while a dialog is open on one of them.
 */
class Station {
    String name;
    String url;

    Station(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
