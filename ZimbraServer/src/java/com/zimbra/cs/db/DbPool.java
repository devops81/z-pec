/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2004, 2005, 2006, 2007, 2008, 2009, 2010, 2011 Zimbra, Inc.
 *
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.Properties;

import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.PoolableConnectionFactory;
import org.apache.commons.dbcp.PoolingDataSource;
import org.apache.commons.pool.impl.GenericObjectPool;

import com.zimbra.common.localconfig.LC;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.SystemUtil;
import com.zimbra.common.util.ValueCounter;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.mailbox.Mailbox;
import com.zimbra.cs.stats.ZimbraPerf;

/**
 * @since Apr 7, 2004
 */
public class DbPool {

    private static PoolingDataSource sPoolingDataSource;
    private static String sRootUrl;
    private static String sLoggerRootUrl;
    private static GenericObjectPool sConnectionPool;
    private static boolean sIsInitialized;

    private static boolean isShutdown;
    private static boolean isUsageWarningEnabled = true;

    static ValueCounter<String> sConnectionStackCounter = new ValueCounter<String>();

    public static class DbConnection {
        private final Connection connection;
        private Throwable mStackTrace;
        Integer mboxId;

        DbConnection(Connection conn) {
            connection = conn;
        }

        DbConnection(Connection conn, Integer mboxId) {
            this(conn);
            this.mboxId = mboxId;
        }

        public Connection getConnection() {
            return connection;
        }

        public void setTransactionIsolation(int level) throws ServiceException {
            try {
                connection.setTransactionIsolation(level);
            } catch (SQLException e) {
                throw ServiceException.FAILURE("setting database connection isolation level", e);
            }
        }

        /**
         * Disable foreign key constraint checking for this Connection.  Used by the mailbox restore code
         * so that it can do a LOAD DATA INFILE without hitting foreign key constraint troubles.
         */
        public void disableForeignKeyConstraints() throws ServiceException {
            PreparedStatement stmt = null;
            try {
                stmt = connection.prepareStatement("SET FOREIGN_KEY_CHECKS=0");
                stmt.execute();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("disabling foreign key constraints", e);
            } finally {
                DbPool.closeStatement(stmt);
            }
        }

        public void enableForeignKeyConstraints() throws ServiceException {
            PreparedStatement stmt = null;
            try {
                stmt = connection.prepareStatement("SET FOREIGN_KEY_CHECKS=1");
                stmt.execute();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("disabling foreign key constraints", e);
            } finally {
                DbPool.closeStatement(stmt);
            }
        }

        public PreparedStatement prepareStatement(String sql) throws SQLException {
            ZimbraPerf.incrementPrepareCount();
            return connection.prepareStatement(sql);
        }

        public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            ZimbraPerf.incrementPrepareCount();
            return connection.prepareStatement(sql, autoGeneratedKeys);
        }

        public void rollback() throws ServiceException {
            try {
                connection.rollback();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("rolling back database transaction", e);
            }
        }

        public void commit() throws ServiceException {
            try {
                connection.commit();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("committing database transaction", e);
            }
        }

        public void close() throws ServiceException {
            // first, do any pre-closing ops
            try {
                Db.getInstance().preClose(this);
            } catch (SQLException e) {
                ZimbraLog.sqltrace.warn("DB connection pre-close processing caught exception", e);
            }

            // then actually close the connection
            try {
                connection.close();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("closing database connection", e);
            } finally {
                // Connection is being returned to the pool.  Decrement its stack
                // trace counter.  Null check is required for the stack trace in
                // case this is a maintenance/logger connection, or if dbconn
                // debug logging was turned on between the getConnection() and
                // close() calls.
                if (mStackTrace != null && ZimbraLog.dbconn.isDebugEnabled()) {
                    String stackTrace = SystemUtil.getStackTrace(mStackTrace);
                    synchronized(sConnectionStackCounter) {
                        sConnectionStackCounter.decrement(stackTrace);
                    }
                }
            }
        }

        /** Sets the stack trace used for detecting connection leaks. */
        void setStackTrace(Throwable t) {
            mStackTrace = t;
        }

        public void closeQuietly() {
            try {
                if (!connection.isClosed()) {
                    close(); //need to do all the pre/post closing ops
                }
            } catch (ServiceException e) {
                ZimbraLog.sqltrace.warn("ServiceException ignored", e);
            } catch (SQLException e) {
                ZimbraLog.sqltrace.warn("SQLException ignored", e);
            }
        }

        public void closeQuietly(Statement stmt) {
            if (stmt == null) {
                return;
            }
            try {
                stmt.close();
            } catch (SQLException e) {
                ZimbraLog.sqltrace.warn("SQL error ignored", e);
            }
        }

        public void closeQuietly(ResultSet rs) {
            if (rs == null) {
                return;
            }
            try {
                rs.close();
            } catch (SQLException e) {
                ZimbraLog.sqltrace.warn("SQL error ignored", e);
            }
        }
    }

    static abstract class PoolConfig {
        String mDriverClassName;
        int mPoolSize;
        String mRootUrl;
        String mConnectionUrl;
        String mLoggerUrl;
        boolean mSupportsStatsCallback;
        Properties mDatabaseProperties;
        byte whenExhaustedAction = GenericObjectPool.WHEN_EXHAUSTED_BLOCK;
    }

    /**
     * Initializes the connection pool.  Applications that access the
     * database must call this method before calling {@link DbPool#getConnection}.
     */
    public synchronized static void startup() {
        if (isInitialized()) {
            return;
        }
        PoolConfig pconfig = Db.getInstance().getPoolConfig();

        String drivers = System.getProperty("jdbc.drivers");
        if (drivers == null)
            System.setProperty("jdbc.drivers", pconfig.mDriverClassName);

        sRootUrl = pconfig.mRootUrl;
        sLoggerRootUrl = pconfig.mLoggerUrl;
        sIsInitialized = true;
        waitForDatabase();
    }

    private static void waitForDatabase() {
        DbConnection conn = null;
        final int RETRY_SECONDS = 5;

        while (conn == null) {
            try {
                conn = DbPool.getConnection();
            } catch (ServiceException e) {
                ZimbraLog.misc.warn("Could not establish a connection to the database.  Retrying in %d seconds.",
                    RETRY_SECONDS, e);
                try {
                    Thread.sleep(RETRY_SECONDS * 1000);
                } catch (InterruptedException e2) {
                }
            }
        }

        DbPool.quietClose(conn);
    }

    private static boolean isInitialized() {
        return sIsInitialized;
    }

    /**
     * Updates cached settings, based on the latest LDAP values.
     */
    public static void loadSettings() {
        try {
            long slowThreshold = Provisioning.getInstance().getLocalServer().getDatabaseSlowSqlThreshold();
            DebugPreparedStatement.setSlowSqlThreshold(slowThreshold);
        } catch (ServiceException e) {
            ZimbraLog.system.warn("Unable to set slow SQL threshold.", e);
        }
    }

    /** Initializes the connection pool. */
    private static synchronized PoolingDataSource getPool() {
        if (isShutdown)
            throw new RuntimeException("DbPool permanently shutdown");

        if (sPoolingDataSource != null)
            return sPoolingDataSource;

        PoolConfig pconfig = Db.getInstance().getPoolConfig();
        sConnectionPool = new GenericObjectPool(null, pconfig.mPoolSize, pconfig.whenExhaustedAction, -1, pconfig.mPoolSize);
        ConnectionFactory cfac = ZimbraConnectionFactory.getConnectionFactory(pconfig);

        boolean defAutoCommit = false, defReadOnly = false;
        new PoolableConnectionFactory(cfac, sConnectionPool, null, null, defReadOnly, defAutoCommit);

        try {
            Class.forName(pconfig.mDriverClassName).newInstance(); //derby requires the .newInstance() call
            Class.forName("org.apache.commons.dbcp.PoolingDriver");
        } catch (Exception e) {
            ZimbraLog.system.fatal("can't instantiate DB driver/pool class", e);
            System.exit(1);
        }

        try {
            PoolingDataSource pds = new PoolingDataSource(sConnectionPool);
            pds.setAccessToUnderlyingConnectionAllowed(true);

            Db.getInstance().startup(pds, pconfig.mPoolSize);

            sPoolingDataSource = pds;
        } catch (SQLException e) {
            ZimbraLog.system.fatal("can't initialize connection pool", e);
            System.exit(1);
        }

        if (pconfig.mSupportsStatsCallback)
            ZimbraPerf.addStatsCallback(new DbStats());

        return sPoolingDataSource;
    }

    /**
     * return a connection to use for the zimbra database.
     * This must not be called while thread also owns an open connection to a mailbox db.
     * @param
     * @return
     * @throws ServiceException
     */
    public static DbConnection getConnection() throws ServiceException {
        return getConnection(null);
    }

    public static DbConnection getConnection(Mailbox mbox) throws ServiceException {
        if (!isInitialized()) {
            throw ServiceException.FAILURE("Database connection pool not initialized.", null);
        }
        Integer mboxId = mbox != null ? mbox.getId() : -1; //-1 == zimbra db and/or initialization where mbox isn't known yet
        try {
            Db.getInstance().preOpen(mboxId);
            long start = ZimbraPerf.STOPWATCH_DB_CONN.start();

            // If the connection pool is overutilized, warn about potential leaks
            PoolingDataSource pool = getPool();
            checkPoolUsage();

            Connection dbconn = null;
            DbConnection conn = null;
            try {
                dbconn = pool.getConnection();

                if (dbconn.getAutoCommit() != false)
                    dbconn.setAutoCommit(false);

                // We want READ COMMITTED transaction isolation level for duplicate
                // handling code in BucketBlobStore.newBlobInfo().
                if (Db.supports(Db.Capability.READ_COMMITTED_ISOLATION))
                    dbconn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

                conn = new DbConnection(dbconn, mboxId);
                Db.getInstance().postOpen(conn);
            } catch (SQLException e) {
                try {
                    if (dbconn != null && !dbconn.isClosed())
                        dbconn.close();
                } catch (SQLException e2) {
                    ZimbraLog.sqltrace.warn("DB connection close caught exception", e);
                }
                throw ServiceException.FAILURE("getting database connection", e);
            }

            // If we're debugging, update the counter with the current stack trace
            if (ZimbraLog.dbconn.isDebugEnabled()) {
                Throwable t = new Throwable();
                conn.setStackTrace(t);

                String stackTrace = SystemUtil.getStackTrace(t);
                synchronized (sConnectionStackCounter) {
                    sConnectionStackCounter.increment(stackTrace);
                }
            }
            if (mbox != null)
                Db.registerDatabaseInterest(conn, mbox);

            ZimbraPerf.STOPWATCH_DB_CONN.stop(start);
            return conn;
        } catch (ServiceException se) {
            //if connection open fails unlock
            Db.getInstance().abortOpen(mboxId);
            throw se;
        }
    }

    private static void checkPoolUsage() {
        int numActive = sConnectionPool.getNumActive();
        int maxActive = sConnectionPool.getMaxActive();

        if (numActive <= maxActive * 0.75)
            return;

        String stackTraceMsg = "Turn on debug logging for zimbra.dbconn to see stack traces of connections not returned to the pool.";
        if (ZimbraLog.dbconn.isDebugEnabled()) {
            StringBuilder buf = new StringBuilder();
            synchronized (sConnectionStackCounter) {
                Iterator<String> i = sConnectionStackCounter.iterator();
                while (i.hasNext()) {
                    String stackTrace = i.next();
                    int count = sConnectionStackCounter.getCount(stackTrace);
                    if (count == 0) {
                        i.remove();
                    } else {
                        buf.append(count + " connections allocated at " + stackTrace + "\n");
                    }
                }
            }
            stackTraceMsg = buf.toString();
        }
        String logMsg = "Connection pool is 75%% utilized (%d connections out of a maximum of %d in use).  %s";
        if (isUsageWarningEnabled) {
            ZimbraLog.dbconn.warn(logMsg, numActive, maxActive, stackTraceMsg);
        } else {
            ZimbraLog.dbconn.debug(logMsg, numActive, maxActive, stackTraceMsg);
        }
    }

    /**
     * Returns a new database connection for maintenance operations, such as
     * restore. Does not specify the name of the default database. This
     * connection is created outside the context of the database connection
     * pool.
     */
    public static DbConnection getMaintenanceConnection() throws ServiceException {
        try {
            String user = LC.zimbra_mysql_user.value();
            String pwd = LC.zimbra_mysql_password.value();
            Connection conn = DriverManager.getConnection(sRootUrl + "?user=" + user + "&password=" + pwd);
            conn.setAutoCommit(false);
            return new DbConnection(conn);
        } catch (SQLException e) {
            throw ServiceException.FAILURE("getting database maintenance connection", e);
        }
    }

    /**
     * Returns a new database connection for logger use.
     * Does not specify the name of the default database. This
     * connection is created outside the context of the database connection
     * pool.
     */
    public static DbConnection getLoggerConnection() throws ServiceException {
        try {
            String user = LC.zimbra_mysql_user.value();
            String pwd = null;
            Connection conn = DriverManager.getConnection(sLoggerRootUrl + "?user=" + user + "&password=" + pwd);
            return new DbConnection(conn);
        } catch (SQLException e) {
            throw ServiceException.FAILURE("getting database logger connection", e);
        }
    }

    /**
     * Closes the specified connection (if not <code>null</code>), catches any
     * exceptions, and logs them.
     */
    public static void quietClose(DbConnection conn) {
        if (conn != null) {
            try {
                if (conn.getConnection() != null && !conn.getConnection().isClosed())
                    conn.close();
            } catch (SQLException e) {
                ZimbraLog.sqltrace.warn("quietClose caught exception", e);
            } catch (ServiceException e) {
                ZimbraLog.sqltrace.warn("quietClose caught exception", e);
            }
        }
    }

    /**
     * Does a rollback on the specified connection (if not <code>null</code>),
     * catches any exceptions, and logs them.
     */
    public static void quietRollback(DbConnection conn) {
        if (conn != null) {
            try {
                if (conn.getConnection() != null && !conn.getConnection().isClosed())
                    conn.rollback();
            } catch (SQLException e) {
                ZimbraLog.sqltrace.warn("quietRollback caught exception", e);
            } catch (ServiceException e) {
                ZimbraLog.sqltrace.warn("quietRollback caught exception", e);
            }
        }
    }

    /**
     * Closes a statement and wraps any resulting exception in a ServiceException.
     * @param stmt
     * @throws ServiceException
     */
    public static void closeStatement(Statement stmt) throws ServiceException {
        if (stmt != null) {
            try {
                stmt.close();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("closing statement", e);
            }
        }
    }

    public static void quietCloseStatement(Statement stmt) {
        try {
            if (stmt != null)
                stmt.close();
        } catch (SQLException e) { }
    }

    /**
     * Closes a ResultSet and wraps any resulting exception in a ServiceException.
     * @param rs
     * @throws ServiceException
     */
    public static void closeResults(ResultSet rs) throws ServiceException {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException e) {
                throw ServiceException.FAILURE("closing statement", e);
            }
        }
    }

    /**
     * Returns the number of connections currently in use.
     */
    public static int getSize() {
        return sConnectionPool.getNumActive();
    }

    /**
     * This is only to be used by DbOfflineMigration to completely close connection to Derby.
     * Note that this doesn't permanently shutdown.  A new getPool() call will restart connections.
     *
     * @throws Exception
     */
    static synchronized void close() throws Exception {
        if (sConnectionPool != null) {
            sConnectionPool.close();
            sConnectionPool = null;
        }
        sPoolingDataSource = null;
        Db.getInstance().shutdown();
    }

    public static synchronized void shutdown() throws Exception {
        isShutdown = true;
        close();
    }

    public static void disableUsageWarning() {
        isUsageWarningEnabled = false;
    }
}