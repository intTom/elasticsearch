/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.ldap;

import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.ldap.sdk.controls.AuthorizationIdentityRequestControl;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRunnable;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.RealmSettings;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapMetaDataResolver;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSession;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSession.GroupsResolver;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils;
import org.elasticsearch.xpack.security.authc.ldap.support.SessionFactory;
import org.elasticsearch.xpack.security.authc.support.CharArrays;
import org.elasticsearch.xpack.ssl.SSLService;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.attributesToSearchFor;
import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.createFilter;
import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.search;
import static org.elasticsearch.xpack.security.authc.ldap.support.LdapUtils.searchForEntry;

/**
 * This Class creates LdapSessions authenticating via the custom Active Directory protocol.  (that being
 * authenticating with a principal name, "username@domain", then searching through the directory to find the
 * user entry in Active Directory that matches the user name).  This eliminates the need for user templates, and simplifies
 * the configuration for windows admins that may not be familiar with LDAP concepts.
 */
class ActiveDirectorySessionFactory extends PoolingSessionFactory {

    static final String AD_DOMAIN_NAME_SETTING = "domain_name";

    static final String AD_GROUP_SEARCH_BASEDN_SETTING = "group_search.base_dn";
    static final String AD_GROUP_SEARCH_SCOPE_SETTING = "group_search.scope";
    static final String AD_USER_SEARCH_BASEDN_SETTING = "user_search.base_dn";
    static final String AD_USER_SEARCH_FILTER_SETTING = "user_search.filter";
    static final String AD_UPN_USER_SEARCH_FILTER_SETTING = "user_search.upn_filter";
    static final String AD_DOWN_LEVEL_USER_SEARCH_FILTER_SETTING = "user_search.down_level_filter";
    static final String AD_USER_SEARCH_SCOPE_SETTING = "user_search.scope";
    private static final String NETBIOS_NAME_FILTER_TEMPLATE = "(netbiosname={0})";
    static final Setting<Boolean> POOL_ENABLED = Setting.boolSetting("user_search.pool.enabled",
            settings -> Boolean.toString(PoolingSessionFactory.BIND_DN.exists(settings)), Setting.Property.NodeScope);

    final DefaultADAuthenticator defaultADAuthenticator;
    final DownLevelADAuthenticator downLevelADAuthenticator;
    final UpnADAuthenticator upnADAuthenticator;

    ActiveDirectorySessionFactory(RealmConfig config, SSLService sslService, ThreadPool threadPool) throws LDAPException {
        super(config, sslService, new ActiveDirectoryGroupsResolver(config.settings()), POOL_ENABLED, () -> {
            if (BIND_DN.exists(config.settings())) {
                return new SimpleBindRequest(getBindDN(config.settings()), BIND_PASSWORD.get(config.settings()));
            } else {
                return new SimpleBindRequest();
            }
        }, () -> {
            if (BIND_DN.exists(config.settings())) {
                final String healthCheckDn = BIND_DN.get(config.settings());
                if (healthCheckDn.isEmpty() && healthCheckDn.indexOf('=') > 0) {
                    return healthCheckDn;
                }
            }
            return config.settings().get(AD_USER_SEARCH_BASEDN_SETTING, config.settings().get(AD_DOMAIN_NAME_SETTING));
        }, threadPool);
        Settings settings = config.settings();
        String domainName = settings.get(AD_DOMAIN_NAME_SETTING);
        if (domainName == null) {
            throw new IllegalArgumentException("missing [" + AD_DOMAIN_NAME_SETTING + "] setting for active directory");
        }
        String domainDN = buildDnFromDomain(domainName);
        defaultADAuthenticator = new DefaultADAuthenticator(config, timeout, ignoreReferralErrors, logger, groupResolver,
                metaDataResolver, domainDN, threadPool);
        downLevelADAuthenticator = new DownLevelADAuthenticator(config, timeout, ignoreReferralErrors, logger, groupResolver,
                metaDataResolver, domainDN, sslService, threadPool);
        upnADAuthenticator = new UpnADAuthenticator(config, timeout, ignoreReferralErrors, logger, groupResolver,
                metaDataResolver, domainDN, threadPool);

    }

    @Override
    protected List<String> getDefaultLdapUrls(Settings settings) {
        return Collections.singletonList("ldap://" + settings.get(AD_DOMAIN_NAME_SETTING) + ":389");
    }

    @Override
    public boolean supportsUnauthenticatedSession() {
        // Strictly, we only support unauthenticated sessions if there is a bind_dn or a connection pool, but the
        // getUnauthenticatedSession... methods handle the situations correctly, so it's OK to always return true here.
        return true;
    }

    @Override
    void getSessionWithPool(LDAPConnectionPool connectionPool, String user, SecureString password, ActionListener<LdapSession> listener) {
        getADAuthenticator(user).authenticate(connectionPool, user, password, threadPool, listener);
    }

    @Override
    void getSessionWithoutPool(String username, SecureString password, ActionListener<LdapSession> listener) {
        try {
            final LDAPConnection connection = LdapUtils.privilegedConnect(serverSet::getConnection);
            getADAuthenticator(username).authenticate(connection, username, password, ActionListener.wrap(listener::onResponse, e -> {
                IOUtils.closeWhileHandlingException(connection);
                listener.onFailure(e);
            }));
        } catch (LDAPException e) {
            listener.onFailure(e);
        }
    }

    @Override
    void getUnauthenticatedSessionWithPool(LDAPConnectionPool connectionPool, String user, ActionListener<LdapSession> listener) {
        getADAuthenticator(user).searchForDN(connectionPool, user, null, Math.toIntExact(timeout.seconds()), ActionListener.wrap(entry -> {
            if (entry == null) {
                listener.onResponse(null);
            } else {
                final String dn = entry.getDN();
                listener.onResponse(new LdapSession(logger, config, connectionPool, dn, groupResolver, metaDataResolver, timeout, null));
            }
        }, listener::onFailure));
    }

    @Override
    void getUnauthenticatedSessionWithoutPool(String user, ActionListener<LdapSession> listener) {
        if (BIND_DN.exists(config.settings()) == false) {
            listener.onResponse(null);
            return;
        }
        try {
            final LDAPConnection connection = LdapUtils.privilegedConnect(serverSet::getConnection);
            final SimpleBindRequest bind = new SimpleBindRequest(getBindDN(config.settings()), BIND_PASSWORD.get(config.settings()));
            LdapUtils.maybeForkThenBind(connection, bind, threadPool, new AbstractRunnable() {

                @Override
                public void onFailure(Exception e) {
                    IOUtils.closeWhileHandlingException(connection);
                    listener.onFailure(e);
                }

                @Override
                protected void doRun() throws Exception {
                    getADAuthenticator(user).searchForDN(connection, user, null, Math.toIntExact(timeout.getSeconds()),
                            ActionListener.wrap(entry -> {
                                if (entry == null) {
                                    IOUtils.close(connection);
                                    listener.onResponse(null);
                                } else {
                                    listener.onResponse(new LdapSession(logger, config, connection, entry.getDN(), groupResolver,
                                            metaDataResolver, timeout, null));
                                }
                            }, e -> {
                                IOUtils.closeWhileHandlingException(connection);
                                listener.onFailure(e);
                            }));

                }
            });
        } catch (LDAPException e) {
            listener.onFailure(e);
        }
    }

    /**
     * @param domain active directory domain name
     * @return LDAP DN, distinguished name, of the root of the domain
     */
    static String buildDnFromDomain(String domain) {
        return "DC=" + domain.replace(".", ",DC=");
    }

    static String getBindDN(Settings settings) {
        String bindDN = BIND_DN.get(settings);
        if (bindDN.isEmpty() == false && bindDN.indexOf('\\') < 0 && bindDN.indexOf('@') < 0 && bindDN.indexOf('=') < 0) {
            bindDN = bindDN + "@" + settings.get(AD_DOMAIN_NAME_SETTING);
        }
        return bindDN;
    }

    public static Set<Setting<?>> getSettings() {
        Set<Setting<?>> settings = new HashSet<>();
        settings.addAll(SessionFactory.getSettings());
        settings.add(Setting.simpleString(AD_DOMAIN_NAME_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_GROUP_SEARCH_BASEDN_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_GROUP_SEARCH_SCOPE_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_USER_SEARCH_BASEDN_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_USER_SEARCH_FILTER_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_UPN_USER_SEARCH_FILTER_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_DOWN_LEVEL_USER_SEARCH_FILTER_SETTING, Setting.Property.NodeScope));
        settings.add(Setting.simpleString(AD_USER_SEARCH_SCOPE_SETTING, Setting.Property.NodeScope));
        settings.add(POOL_ENABLED);
        settings.addAll(PoolingSessionFactory.getSettings());
        return settings;
    }

    ADAuthenticator getADAuthenticator(String username) {
        if (username.indexOf('\\') > 0) {
            return downLevelADAuthenticator;
        } else if (username.indexOf("@") > 0) {
            return upnADAuthenticator;
        }
        return defaultADAuthenticator;
    }

    abstract static class ADAuthenticator {

        private final RealmConfig realm;
        final TimeValue timeout;
        final boolean ignoreReferralErrors;
        final Logger logger;
        final GroupsResolver groupsResolver;
        final LdapMetaDataResolver metaDataResolver;
        final String userSearchDN;
        final LdapSearchScope userSearchScope;
        final String userSearchFilter;
        final String bindDN;
        final String bindPassword; // TODO this needs to be a setting in the secure settings store!
        final ThreadPool threadPool;

        ADAuthenticator(RealmConfig realm, TimeValue timeout, boolean ignoreReferralErrors, Logger logger, GroupsResolver groupsResolver,
                LdapMetaDataResolver metaDataResolver, String domainDN, String userSearchFilterSetting, String defaultUserSearchFilter,
                ThreadPool threadPool) {
            this.realm = realm;
            this.timeout = timeout;
            this.ignoreReferralErrors = ignoreReferralErrors;
            this.logger = logger;
            this.groupsResolver = groupsResolver;
            this.metaDataResolver = metaDataResolver;
            final Settings settings = realm.settings();
            this.bindDN = getBindDN(settings);
            this.bindPassword = BIND_PASSWORD.get(settings);
            this.threadPool = threadPool;
            userSearchDN = settings.get(AD_USER_SEARCH_BASEDN_SETTING, domainDN);
            userSearchScope = LdapSearchScope.resolve(settings.get(AD_USER_SEARCH_SCOPE_SETTING), LdapSearchScope.SUB_TREE);
            userSearchFilter = settings.get(userSearchFilterSetting, defaultUserSearchFilter);
        }

        final void authenticate(LDAPConnection connection, String username, SecureString password, ActionListener<LdapSession> listener) {
            final byte[] passwordBytes = CharArrays.toUtf8Bytes(password.getChars());
            final SimpleBindRequest userBind = new SimpleBindRequest(bindUsername(username), passwordBytes,
                    new AuthorizationIdentityRequestControl());
            LdapUtils.maybeForkThenBind(connection, userBind, threadPool, new ActionRunnable<LdapSession>(listener) {
                @Override
                protected void doRun() throws Exception {
                    final ActionRunnable<LdapSession> searchRunnable = new ActionRunnable<LdapSession>(listener) {
                        @Override
                        protected void doRun() throws Exception {
                            searchForDN(connection, username, password, Math.toIntExact(timeout.seconds()), ActionListener.wrap((entry) -> {
                                if (entry == null) {
                                    // we did not find the user, cannot authenticate in this realm
                                    listener.onFailure(new ElasticsearchSecurityException(
                                            "search for user [" + username + "] by principal name yielded no results"));
                                } else {
                                    listener.onResponse(new LdapSession(logger, realm, connection, entry.getDN(), groupsResolver,
                                            metaDataResolver, timeout, null));
                                }
                            }, e -> {
                                listener.onFailure(e);
                            }));
                        }
                    };
                    if (bindDN.isEmpty()) {
                        searchRunnable.run();
                    } else {
                        final SimpleBindRequest bind = new SimpleBindRequest(bindDN, bindPassword);
                        LdapUtils.maybeForkThenBind(connection, bind, threadPool, searchRunnable);
                    }
                }
            });
        }

        final void authenticate(LDAPConnectionPool pool, String username, SecureString password, ThreadPool threadPool,
                                ActionListener<LdapSession> listener) {
            final byte[] passwordBytes = CharArrays.toUtf8Bytes(password.getChars());
            final SimpleBindRequest bind = new SimpleBindRequest(bindUsername(username), passwordBytes);
            LdapUtils.maybeForkThenBind(pool, bind, threadPool, new ActionRunnable<LdapSession>(listener) {
                @Override
                protected void doRun() throws Exception {
                    searchForDN(pool, username, password, Math.toIntExact(timeout.seconds()), ActionListener.wrap((entry) -> {
                        if (entry == null) {
                            // we did not find the user, cannot authenticate in this realm
                            listener.onFailure(new ElasticsearchSecurityException(
                                    "search for user [" + username + "] by principal name yielded no results"));
                        } else {
                            listener.onResponse(
                                    new LdapSession(logger, realm, pool, entry.getDN(), groupsResolver, metaDataResolver, timeout, null));
                        }
                    }, e -> {
                        listener.onFailure(e);
                    }));
                }
            });
        }

        String bindUsername(String username) {
            return username;
        }

        // pkg-private for testing
        final String getUserSearchFilter() {
            return userSearchFilter;
        }

        abstract void searchForDN(LDAPInterface connection, String username, SecureString password, int timeLimitSeconds,
                                  ActionListener<SearchResultEntry> listener);
    }

    /**
     * This authenticator is used for usernames that do not contain an `@` or `/`. It attempts a bind with the provided username combined
     * with the domain name specified in settings. On AD DS this will work for both upn@domain and samaccountname@domain; AD LDS will only
     * support the upn format
     */
    static class DefaultADAuthenticator extends ADAuthenticator {

        final String domainName;

        DefaultADAuthenticator(RealmConfig realm, TimeValue timeout, boolean ignoreReferralErrors, Logger logger,
                GroupsResolver groupsResolver, LdapMetaDataResolver metaDataResolver, String domainDN, ThreadPool threadPool) {
            super(realm, timeout, ignoreReferralErrors, logger, groupsResolver, metaDataResolver, domainDN, AD_USER_SEARCH_FILTER_SETTING,
                    "(&(objectClass=user)(|(sAMAccountName={0})(userPrincipalName={0}@" + domainName(realm) + ")))", threadPool);
            domainName = domainName(realm);
        }

        private static String domainName(RealmConfig realm) {
            return realm.settings().get(AD_DOMAIN_NAME_SETTING);
        }

        @Override
        void searchForDN(LDAPInterface connection, String username, SecureString password,
                         int timeLimitSeconds, ActionListener<SearchResultEntry> listener) {
            try {
                searchForEntry(connection, userSearchDN, userSearchScope.scope(),
                        createFilter(userSearchFilter, username), timeLimitSeconds,
                        ignoreReferralErrors, listener,
                        attributesToSearchFor(groupsResolver.attributes()));
            } catch (LDAPException e) {
                listener.onFailure(e);
            }
        }

        @Override
        String bindUsername(String username) {
            return username + "@" + domainName;
        }
    }

    /**
     * Active Directory calls the format <code>DOMAIN\\username</code> down-level credentials and
     * this class contains the logic necessary to authenticate this form of a username
     */
    static class DownLevelADAuthenticator extends ADAuthenticator {
        static final String DOWN_LEVEL_FILTER = "(&(objectClass=user)(sAMAccountName={0}))";
        Cache<String, String> domainNameCache = CacheBuilder.<String, String>builder().setMaximumWeight(100).build();

        final String domainDN;
        final Settings settings;
        final SSLService sslService;
        final RealmConfig config;

        DownLevelADAuthenticator(RealmConfig config, TimeValue timeout, boolean ignoreReferralErrors, Logger logger,
                GroupsResolver groupsResolver, LdapMetaDataResolver metaDataResolver, String domainDN, SSLService sslService,
                ThreadPool threadPool) {
            super(config, timeout, ignoreReferralErrors, logger, groupsResolver, metaDataResolver, domainDN,
                    AD_DOWN_LEVEL_USER_SEARCH_FILTER_SETTING, DOWN_LEVEL_FILTER, threadPool);
            this.domainDN = domainDN;
            this.settings = config.settings();
            this.sslService = sslService;
            this.config = config;
        }

        @Override
        void searchForDN(LDAPInterface connection, String username, SecureString password, int timeLimitSeconds,
                         ActionListener<SearchResultEntry> listener) {
            String[] parts = username.split("\\\\");
            assert parts.length == 2;
            final String netBiosDomainName = parts[0];
            final String accountName = parts[1];

            netBiosDomainNameToDn(connection, netBiosDomainName, username, password, timeLimitSeconds, ActionListener.wrap((domainDN) -> {
                if (domainDN == null) {
                    listener.onResponse(null);
                } else {
                    searchForEntry(connection, domainDN, LdapSearchScope.SUB_TREE.scope(), createFilter(userSearchFilter, accountName),
                            timeLimitSeconds, ignoreReferralErrors, listener, attributesToSearchFor(groupsResolver.attributes()));
                }
            }, listener::onFailure));
        }

        void netBiosDomainNameToDn(LDAPInterface ldapInterface, String netBiosDomainName, String username, SecureString password,
                                   int timeLimitSeconds, ActionListener<String> listener) {
            LDAPConnection ldapConnection = null;
            try {
                final Filter filter = createFilter(NETBIOS_NAME_FILTER_TEMPLATE, netBiosDomainName);
                final String cachedName = domainNameCache.get(netBiosDomainName);
                if (cachedName != null) {
                    listener.onResponse(cachedName);
                } else if (usingGlobalCatalog(ldapInterface) == false) {
                    search(ldapInterface, domainDN, LdapSearchScope.SUB_TREE.scope(), filter, timeLimitSeconds, ignoreReferralErrors,
                            ActionListener.wrap((results) -> handleSearchResults(results, netBiosDomainName, domainNameCache, listener),
                                    listener::onFailure),
                            "ncname");
                } else {
                    // the global catalog does not replicate the necessary information to map a
                    // netbios dns name to a DN so we need to instead connect to the normal ports.
                    // This code uses the standard ports to avoid adding even more settings and is
                    // probably ok as most AD users do not use non-standard ports
                    if (ldapInterface instanceof LDAPConnection) {
                        ldapConnection = (LDAPConnection) ldapInterface;
                    } else {
                        ldapConnection = LdapUtils.privilegedConnect(((LDAPConnectionPool) ldapInterface)::getConnection);
                    }
                    final LDAPConnection finalLdapConnection = ldapConnection;
                    final LDAPConnection searchConnection = LdapUtils.privilegedConnect(
                            () -> new LDAPConnection(finalLdapConnection.getSocketFactory(), connectionOptions(config, sslService, logger),
                                    finalLdapConnection.getConnectedAddress(), finalLdapConnection.getSSLSession() != null ? 636 : 389));
                    final byte[] passwordBytes = CharArrays.toUtf8Bytes(password.getChars());
                    final SimpleBindRequest bind = bindDN.isEmpty()
                            ? new SimpleBindRequest(username, passwordBytes)
                            : new SimpleBindRequest(bindDN, bindPassword);
                    LdapUtils.maybeForkThenBind(searchConnection, bind, threadPool, new ActionRunnable<String>(listener) {
                        @Override
                        protected void doRun() throws Exception {
                            search(searchConnection, domainDN, LdapSearchScope.SUB_TREE.scope(), filter, timeLimitSeconds,
                                    ignoreReferralErrors,
                                    ActionListener.wrap(
                                            results -> {
                                                IOUtils.close(searchConnection);
                                                handleSearchResults(results, netBiosDomainName, domainNameCache, listener);
                                            }, e -> {
                                                IOUtils.closeWhileHandlingException(searchConnection);
                                                listener.onFailure(e);
                                            }),
                                    "ncname");
                        }

                        @Override
                        public void onFailure(Exception e) {
                            IOUtils.closeWhileHandlingException(searchConnection);
                            listener.onFailure(e);
                        };
                    });
                }
            } catch (LDAPException e) {
                listener.onFailure(e);
            } finally {
                if (ldapInterface instanceof LDAPConnectionPool && ldapConnection != null) {
                    ((LDAPConnectionPool) ldapInterface).releaseConnection(ldapConnection);
                }
            }
        }

        static void handleSearchResults(List<SearchResultEntry> results, String netBiosDomainName,
                                        Cache<String, String> domainNameCache,
                                        ActionListener<String> listener) {
            Optional<SearchResultEntry> entry = results.stream()
                    .filter((r) -> r.hasAttribute("ncname"))
                    .findFirst();
            if (entry.isPresent()) {
                final String value = entry.get().getAttributeValue("ncname");
                try {
                    domainNameCache.computeIfAbsent(netBiosDomainName, (s) -> value);
                } catch (ExecutionException e) {
                    throw new AssertionError("failed to load constant non-null value", e);
                }
                listener.onResponse(value);
            } else {
                listener.onResponse(null);
            }
        }

        static boolean usingGlobalCatalog(LDAPInterface ldap) throws LDAPException {
            if (ldap instanceof LDAPConnection) {
                return usingGlobalCatalog((LDAPConnection) ldap);
            } else {
                LDAPConnectionPool pool = (LDAPConnectionPool) ldap;
                LDAPConnection connection = null;
                try {
                    connection = LdapUtils.privilegedConnect(pool::getConnection);
                    return usingGlobalCatalog(connection);
                } finally {
                    if (connection != null) {
                        pool.releaseConnection(connection);
                    }
                }
            }
        }

        private static boolean usingGlobalCatalog(LDAPConnection ldapConnection) {
            return ldapConnection.getConnectedPort() == 3268 || ldapConnection.getConnectedPort() == 3269;
        }
    }

    /**
     * Authenticates user principal names provided by the user (eq user@domain). Note this authenticator does not currently support
     * UPN suffixes that are different than the actual domain name.
     */
    static class UpnADAuthenticator extends ADAuthenticator {

        static final String UPN_USER_FILTER = "(&(objectClass=user)(userPrincipalName={1}))";

        UpnADAuthenticator(RealmConfig config, TimeValue timeout, boolean ignoreReferralErrors, Logger logger,
                GroupsResolver groupsResolver, LdapMetaDataResolver metaDataResolver, String domainDN, ThreadPool threadPool) {
            super(config, timeout, ignoreReferralErrors, logger, groupsResolver, metaDataResolver, domainDN,
                    AD_UPN_USER_SEARCH_FILTER_SETTING, UPN_USER_FILTER, threadPool);
            if (userSearchFilter.contains("{0}")) {
                new DeprecationLogger(logger).deprecated("The use of the account name variable {0} in the setting ["
                        + RealmSettings.getFullSettingKey(config, AD_UPN_USER_SEARCH_FILTER_SETTING) +
                        "] has been deprecated and will be removed in a future version!");
            }
        }

        @Override
        void searchForDN(LDAPInterface connection, String username, SecureString password, int timeLimitSeconds,
                         ActionListener<SearchResultEntry> listener) {
            String[] parts = username.split("@");
            assert parts.length == 2 : "there should have only been two values for " + username + " after splitting on '@'";
            final String accountName = parts[0];
            try {
                Filter filter = createFilter(userSearchFilter, accountName, username);
                searchForEntry(connection, userSearchDN, LdapSearchScope.SUB_TREE.scope(), filter,
                        timeLimitSeconds, ignoreReferralErrors, listener,
                        attributesToSearchFor(groupsResolver.attributes()));
            } catch (LDAPException e) {
                listener.onFailure(e);
            }
        }
    }
}
