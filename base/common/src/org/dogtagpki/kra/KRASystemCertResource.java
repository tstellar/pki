package org.dogtagpki.kra;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import org.jboss.resteasy.annotations.ClientResponseType;

import com.netscape.certsrv.cert.CertData;

@Path("config/cert")
public interface KRASystemCertResource {

    @GET
    @Path("transport")
    @ClientResponseType(entityType=CertData.class)
    public Response getTransportCert() throws Exception;
}
