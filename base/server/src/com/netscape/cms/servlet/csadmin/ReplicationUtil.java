// --- BEGIN COPYRIGHT BLOCK ---
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; version 2 of the License.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along
// with this program; if not, write to the Free Software Foundation, Inc.,
// 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
//
// (C) 2018 Red Hat, Inc.
// All rights reserved.
// --- END COPYRIGHT BLOCK ---
package com.netscape.cms.servlet.csadmin;

import java.io.IOException;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netscape.cmscore.apps.CMS;
import com.netscape.cmscore.apps.CMSEngine;
import com.netscape.cmscore.apps.DatabaseConfig;
import com.netscape.cmscore.apps.EngineConfig;
import com.netscape.cmscore.apps.PreOpConfig;
import com.netscape.cmscore.ldapconn.LDAPConfig;
import com.netscape.cmscore.ldapconn.LDAPConnectionConfig;
import com.netscape.cmsutil.ldap.LDAPUtil;

import netscape.ldap.LDAPAttribute;
import netscape.ldap.LDAPAttributeSet;
import netscape.ldap.LDAPConnection;
import netscape.ldap.LDAPEntry;
import netscape.ldap.LDAPException;
import netscape.ldap.LDAPModification;
import netscape.ldap.LDAPSearchResults;

public class ReplicationUtil {

    public final static Logger logger = LoggerFactory.getLogger(ReplicationUtil.class);

    public static void setupReplication(
            LDAPConfigurator masterConfigurator,
            LDAPConfigurator replicaConfigurator,
            String replica_replicationpwd,
            int masterReplicationPort,
            int cloneReplicationPort,
            String replicationSecurity) throws Exception {

        logger.info("ReplicationUtil: setting up replication");

        CMSEngine engine = CMS.getCMSEngine();
        EngineConfig cs = engine.getConfig();
        PreOpConfig preopConfig = cs.getPreOpConfig();
        DatabaseConfig dbConfig = cs.getDatabaseConfig();

        LDAPConfig masterCfg = preopConfig.getSubStore("internaldb.master", LDAPConfig.class);
        LDAPConnectionConfig masterConnCfg = masterCfg.getConnectionConfig();

        LDAPConfig replicaCfg = cs.getInternalDBConfig();
        LDAPConnectionConfig replicaConnCfg = replicaCfg.getConnectionConfig();

        String machinename = cs.getHostname();
        String instanceId = cs.getInstanceID();

        String secure = replicaConnCfg.getString("secureConn");

        String master_hostname = masterConnCfg.getString("host", "");
        String master_replicationpwd = preopConfig.getString("internaldb.master.replication.password", "");

        String replica_hostname = replicaConnCfg.getString("host", "");

        String basedn = replicaCfg.getBaseDN();
        String suffix = replicaCfg.getBaseDN();

        String masterAgreementName = "masterAgreement1-" + machinename + "-" + instanceId;
        String cloneAgreementName = "cloneAgreement1-" + machinename + "-" + instanceId;

        LDAPConnection masterConn = masterConfigurator.getConnection();
        LDAPConnection replicaConn = replicaConfigurator.getConnection();

        try {
            String replicadn = "cn=replica,cn=\"" + suffix + "\",cn=mapping tree,cn=config";
            logger.debug("ReplicationUtil: replica DN: " + replicadn);

            String masterBindUser = "Replication Manager " + masterAgreementName;
            logger.debug("ReplicationUtil: creating replication manager on master");
            masterConfigurator.createSystemContainer();
            masterConfigurator.createReplicationManager(masterBindUser, master_replicationpwd);

            String masterChangelog = masterConfigurator.getInstanceDir() + "/changelogs";
            logger.debug("ReplicationUtil: creating master changelog dir: " + masterChangelog);
            createChangeLog(masterConn, masterChangelog);

            String cloneBindUser = "Replication Manager " + cloneAgreementName;
            logger.debug("ReplicationUtil: creating replication manager on replica");
            replicaConfigurator.createSystemContainer();
            replicaConfigurator.createReplicationManager(cloneBindUser, replica_replicationpwd);

            String replicaChangelog = replicaConfigurator.getInstanceDir() + "/changelogs";
            logger.debug("ReplicationUtil: creating replica changelog dir: " + masterChangelog);
            createChangeLog(replicaConn, replicaChangelog);

            int replicaId = dbConfig.getInteger("beginReplicaNumber", 1);

            logger.debug("ReplicationUtil: enabling replication on master");
            replicaId = enableReplication(replicadn, masterConn, masterBindUser, basedn, replicaId);

            logger.debug("ReplicationUtil: enabling replication on replica");
            replicaId = enableReplication(replicadn, replicaConn, cloneBindUser, basedn, replicaId);

            logger.debug("ReplicationUtil: replica ID: " + replicaId);
            dbConfig.putString("beginReplicaNumber", Integer.toString(replicaId));

            logger.debug("ReplicationUtil: creating master replication agreement");
            createReplicationAgreement(replicadn, masterConn, masterAgreementName,
                    replica_hostname, cloneReplicationPort, replica_replicationpwd, basedn,
                    cloneBindUser, secure, replicationSecurity);

            logger.debug("ReplicationUtil: creating replica replication agreement");
            createReplicationAgreement(replicadn, replicaConn, cloneAgreementName,
                    master_hostname, masterReplicationPort, master_replicationpwd, basedn,
                    masterBindUser, secure, replicationSecurity);

            logger.debug("ReplicationUtil: initializing replication consumer");
            initializeConsumer(replicadn, masterConn, masterAgreementName);

            while (!replicationDone(replicadn, masterConn, masterAgreementName)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            String status = replicationStatus(replicadn, masterConn, masterAgreementName);
            if (!(status.startsWith("Error (0) ") || status.startsWith("0 "))) {
                String message = "ReplicationUtil: replication consumer initialization failed " +
                    "(against " + masterConn.getHost() + ":" + masterConn.getPort() + "): " + status;
                logger.error(message);
                throw new IOException(message);
            }

            logger.debug("ReplicationUtil: replication setup complete");

        } catch (Exception e) {
            logger.error("ReplicationUtil: Unable to setup replication: " + e.getMessage(), e);
            throw new IOException("Unable to setup replication: " + e.getMessage(), e);
        }
    }

    public static void createChangeLog(LDAPConnection conn, String dir)
            throws LDAPException {

        LDAPEntry entry = null;

        String dn = "cn=changelog5,cn=config";
        logger.debug("ReplicationUtil: creating " + dn);

        try {
            LDAPAttributeSet attrs = new LDAPAttributeSet();
            attrs.add(new LDAPAttribute("objectClass", "top"));
            attrs.add(new LDAPAttribute("objectClass", "extensibleObject"));
            attrs.add(new LDAPAttribute("cn", "changelog5"));
            attrs.add(new LDAPAttribute("nsslapd-changelogdir", dir));
            entry = new LDAPEntry(dn, attrs);
            conn.add(entry);

        } catch (LDAPException e) {
            if (e.getLDAPResultCode() == LDAPException.ENTRY_ALREADY_EXISTS) {
                logger.warn("ReplicationUtil: Changelog entry has already used");
                /* leave it, dont delete it because it will have operation error */

            } else {
                logger.error("ReplicationUtil: Failed to create changelog entry. Exception: " + e);
                throw e;
            }
        }
    }

    public static int enableReplication(String replicadn, LDAPConnection conn, String bindUser, String basedn, int id)
            throws LDAPException {

        LDAPEntry entry = null;

        String bindDN = "cn=" + LDAPUtil.escapeRDNValue(bindUser) + ",ou=csusers,cn=config";

        logger.debug("ReplicationUtil: creating " + replicadn);
        logger.debug("ReplicationUtil: nsDS5ReplicaRoot: " + basedn);
        logger.debug("ReplicationUtil: nsDS5ReplicaBindDN: " + bindDN);
        logger.debug("ReplicationUtil: nsDS5ReplicaId: " + id);

        try {
            LDAPAttributeSet attrs = new LDAPAttributeSet();
            attrs.add(new LDAPAttribute("objectclass", "top"));
            attrs.add(new LDAPAttribute("objectclass", "nsDS5Replica"));
            attrs.add(new LDAPAttribute("objectclass", "extensibleobject"));
            attrs.add(new LDAPAttribute("cn", "replica"));
            attrs.add(new LDAPAttribute("nsDS5ReplicaRoot", basedn));
            attrs.add(new LDAPAttribute("nsDS5ReplicaType", "3"));
            attrs.add(new LDAPAttribute("nsDS5ReplicaBindDN", bindDN));
            attrs.add(new LDAPAttribute("nsDS5ReplicaId", Integer.toString(id)));
            attrs.add(new LDAPAttribute("nsds5flags", "1"));
            entry = new LDAPEntry(replicadn, attrs);
            conn.add(entry);

        } catch (LDAPException e) {
            if (e.getLDAPResultCode() == LDAPException.ENTRY_ALREADY_EXISTS) {
                /* BZ 470918 -we cant just add the new dn.  We need to do a replace instead
                 * until the DS code is fixed */
                logger.warn("ReplicationUtil: " + replicadn + " has already been used");

                try {
                    entry = conn.read(replicadn);
                    LDAPAttribute attr = entry.getAttribute("nsDS5ReplicaBindDN");
                    attr.addValue("cn=" + LDAPUtil.escapeRDNValue(bindUser) + ",ou=csusers,cn=config");
                    LDAPModification mod = new LDAPModification(LDAPModification.REPLACE, attr);
                    conn.modify(replicadn, mod);


                } catch (LDAPException ee) {
                    logger.warn("ReplicationUtil: Unable to modify replica entry: " + ee.getMessage(), ee);
                }
                return id;

            } else {
                logger.warn("ReplicationUtil: Unable to create replica entry: " + e.getMessage(), e);
                return id;
            }
        }

        logger.info("ReplicationUtil: Successfully created " + replicadn + " entry.");
        return id + 1;
    }

    public static void createReplicationAgreement(String replicadn, LDAPConnection conn, String name,
            String replicahost, int replicaport, String replicapwd, String basedn, String bindUser,
            String secure, String replicationSecurity) throws LDAPException {

        LDAPEntry entry = null;

        String dn = "cn=" + LDAPUtil.escapeRDNValue(name) + "," + replicadn;
        String bindDN = "cn=" + LDAPUtil.escapeRDNValue(bindUser) + ",ou=csusers,cn=config";

        logger.debug("ReplicationUtil: creating " + dn);
        logger.debug("ReplicationUtil: nsDS5ReplicaRoot: " + basedn);
        logger.debug("ReplicationUtil: nsDS5ReplicaHost: " + replicahost);
        logger.debug("ReplicationUtil: nsDS5ReplicaPort: " + replicaport);
        logger.debug("ReplicationUtil: nsDS5ReplicaBindDN: " + bindDN);
        logger.debug("ReplicationUtil: nsDS5ReplicaTransportInfo: " + replicationSecurity);
        logger.debug("ReplicationUtil: description: " + name);

        try {
            LDAPAttributeSet attrs = new LDAPAttributeSet();
            attrs.add(new LDAPAttribute("objectclass", "top"));
            attrs.add(new LDAPAttribute("objectclass", "nsds5replicationagreement"));
            attrs.add(new LDAPAttribute("cn", name));
            attrs.add(new LDAPAttribute("nsDS5ReplicaRoot", basedn));
            attrs.add(new LDAPAttribute("nsDS5ReplicaHost", replicahost));

            attrs.add(new LDAPAttribute("nsDS5ReplicaPort", "" + replicaport));
            attrs.add(new LDAPAttribute("nsDS5ReplicaBindDN", bindDN));
            attrs.add(new LDAPAttribute("nsDS5ReplicaBindMethod", "Simple"));
            attrs.add(new LDAPAttribute("nsds5replicacredentials", replicapwd));

            if (replicationSecurity.equals("SSL")) {
                attrs.add(new LDAPAttribute("nsDS5ReplicaTransportInfo", "SSL"));
            } else if (replicationSecurity.equals("TLS")) {
                attrs.add(new LDAPAttribute("nsDS5ReplicaTransportInfo", "TLS"));
            }

            attrs.add(new LDAPAttribute("description", name));

            entry = new LDAPEntry(dn, attrs);
            conn.add(entry);

        } catch (LDAPException e) {
            if (e.getLDAPResultCode() == LDAPException.ENTRY_ALREADY_EXISTS) {
                logger.warn("ReplicationUtil: " + dn + " has already used");
                try {
                    conn.delete(dn);
                } catch (LDAPException ee) {
                    logger.error("ReplicationUtil: " + ee);
                    throw ee;
                }

                try {
                    conn.add(entry);
                } catch (LDAPException ee) {
                    logger.error("ReplicationUtil: " + ee);
                    throw ee;
                }
            } else {
                logger.error("ReplicationUtil: Unable to create replication agreement: " + e.getMessage(), e);
                throw e;
            }
        }

        logger.info("ReplicationUtil: Successfully created replication agreement " + name);
    }

    public static void initializeConsumer(String replicadn, LDAPConnection conn, String name) throws LDAPException {

        String dn = "cn=" + LDAPUtil.escapeRDNValue(name) + "," + replicadn;
        logger.debug("ReplicationUtil: initializing consumer " + dn);

        LDAPAttribute attr = new LDAPAttribute("nsds5beginreplicarefresh", "start");
        LDAPModification mod = new LDAPModification(LDAPModification.REPLACE, attr);
        conn.modify(dn, mod);
    }

    public static boolean replicationDone(String replicadn, LDAPConnection conn, String name)
            throws LDAPException, IOException {

        String dn = "cn=" + LDAPUtil.escapeRDNValue(name) + "," + replicadn;
        logger.debug("ReplicationUtil: checking " + dn);

        String filter = "(objectclass=*)";
        String[] attrs = { "nsds5beginreplicarefresh" };

        LDAPSearchResults results = conn.search(dn, LDAPConnection.SCOPE_BASE, filter, attrs, true);
        int count = results.getCount();

        if (count < 1) {
            throw new IOException("Replication entry not found: " + dn);
        }

        LDAPEntry entry = results.next();
        LDAPAttribute refresh = entry.getAttribute("nsds5beginreplicarefresh");

        if (refresh == null) {
            return true;
        }

        return false;
    }

    public static String replicationStatus(String replicadn, LDAPConnection conn, String name)
            throws IOException, LDAPException {

        String dn = "cn=" + LDAPUtil.escapeRDNValue(name) + "," + replicadn;
        logger.debug("ReplicationUtil: checking " + dn);

        String filter = "(objectclass=*)";
        String[] attrs = { "nsds5replicalastinitstatus" };

        LDAPSearchResults results = conn.search(dn, LDAPConnection.SCOPE_BASE, filter, attrs, false);

        int count = results.getCount();

        if (count < 1) {
            logger.error("ReplicationUtil: Missing replication entry: " + dn);
            throw new IOException("Missing replication entry: " + dn);
        }

        LDAPEntry entry = results.next();
        LDAPAttribute attr = entry.getAttribute("nsds5replicalastinitstatus");

        if (attr == null) {
            logger.error("ReplicationUtil: Missing attribute: nsds5replicalastinitstatus");
            throw new IOException("Missing attribute: nsDS5ReplicaLastInitStatus");
        }

        @SuppressWarnings("unchecked")
        Enumeration<String> valsInAttr = attr.getStringValues();

        if (!valsInAttr.hasMoreElements()) {
            logger.error("ReplicationUtil: Missing attribute: nsds5replicalastinitstatus");
            throw new IOException("Missing attribute value: nsds5replicalastinitstatus");
        }

        String status = valsInAttr.nextElement();
        logger.debug("ReplicationUtil: nsds5replicalastinitstatus: " + status);

        return status;
    }
}
