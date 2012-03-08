/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

/**
 * // TODO: Explain yourself
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/20/12
 */
@XmlRootElement
public class DtoHostCommand {
    private int id;

    @XmlTransient
    private URI uri;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }
}
