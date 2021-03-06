//
// Copyright Red Hat, Inc.
//
// SPDX-License-Identifier: GPL-2.0-or-later
//
package org.dogtagpki.acme.database;

import java.util.Collection;
import java.util.Date;

import org.dogtagpki.acme.ACMEAccount;
import org.dogtagpki.acme.ACMEAuthorization;
import org.dogtagpki.acme.ACMENonce;
import org.dogtagpki.acme.ACMEOrder;

/**
 * @author Endi S. Dewata
 */
public abstract class ACMEDatabase {

    protected ACMEDatabaseConfig config;

    public ACMEDatabaseConfig getConfig() {
        return config;
    }

    public void setConfig(ACMEDatabaseConfig config) {
        this.config = config;
    }

    public void init() throws Exception {
    }

    public void close() throws Exception {
    }

    public abstract void addNonce(ACMENonce nonce) throws Exception;
    public abstract ACMENonce removeNonce(String value) throws Exception;
    public abstract void removeExpiredNonces(Date currentTime) throws Exception;

    public abstract ACMEAccount getAccount(String accountID) throws Exception;
    public abstract void addAccount(ACMEAccount account) throws Exception;
    public abstract void updateAccount(ACMEAccount account) throws Exception;

    public abstract ACMEOrder getOrder(String orderID) throws Exception;
    public abstract Collection<ACMEOrder> getOrdersByAuthorizationAndStatus(
            String authzID,
            String status)
        throws Exception;

    /**
     * This method returns the order record that created the certificate.
     * The order record may be purged by the server at some point, so this
     * record is not guaranteed to be available for all certificates.
     *
     * A certificate has at most one order record that corresponds to it.
     * Only valid orders will have a certificate associated to them. If an
     * order fails during processing, no certificates will be issued for it.
     * If a certificate is renewed, a new order will be created which will
     * then have a new certificate.
     *
     * @param certID The ID of the certificate.
     * @return The order record corresponding to the provided certificate.
     * @throws Exception
     */
    public abstract ACMEOrder getOrderByCertificate(String certID) throws Exception;

    public abstract void addOrder(ACMEOrder order) throws Exception;
    public abstract void updateOrder(ACMEOrder order) throws Exception;

    public abstract ACMEAuthorization getAuthorization(String authzID) throws Exception;
    public abstract ACMEAuthorization getAuthorizationByChallenge(String challengeID) throws Exception;
    public abstract void addAuthorization(ACMEAuthorization authorization) throws Exception;
    public abstract void updateAuthorization(ACMEAuthorization authorization) throws Exception;
}
