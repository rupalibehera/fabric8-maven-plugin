/**
 * Copyright 2005-2015 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.fabric8.project;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import javax.net.ssl.*;

import com.jcraft.jsch.*;
import io.fabric8.maven.core.util.FileUtil;
import io.fabric8.maven.core.util.GitUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.util.FS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class GitUtils {
    private static final transient Logger LOG = LoggerFactory.getLogger(GitUtils.class);

    public static File getRootGitDirectory(Git git) {
        return git.getRepository().getDirectory().getParentFile();
    }

    public static String toString(Collection<RemoteRefUpdate> updates) {
        StringBuilder builder = new StringBuilder();
        for (RemoteRefUpdate update : updates) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(update.getMessage() + " " + update.getRemoteName() + " " + update.getNewObjectId());
        }
        return builder.toString();
    }

    public static void disableSslCertificateChecks() {
        LOG.info("Trusting all SSL certificates");

        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{ new TrustEverythingSSLTrustManager()}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            // bypass host name check, too.
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String s, SSLSession sslSession) {
                    return true;
                }
            });
        } catch (NoSuchAlgorithmException e) {
            LOG.warn("Failed to bypass certificate check", e);
        } catch (KeyManagementException e) {
            LOG.warn("Failed to bypass certificate check", e);
        }
    }

    /**
     * Returns the remote git URL for the given branch
     */
    public static String getRemoteURL(Git git, String branch) {
        Repository repository = git.getRepository();
        return getRemoteURL(repository, branch);
    }

    /**
     * Returns the remote repository for the current branch in the given repository
     */
    public static String getRemoteURL(Repository repository) throws IOException {
        if (repository != null) {
            return getRemoteURL(repository, "origin");
        }
        return null;
    }

    public static String getRemoteURL(Repository repository, String remoteName) {
        if (repository != null) {
            StoredConfig config = repository.getConfig();
            if (config != null) {
                return config.getString("remote", remoteName, "url");
            }
        }
        return null;
    }

    public static void configureBranch(Git git, String branch, String origin, String remoteRepository) {
        // lets update the merge config
        if (!StringUtils.isBlank(branch)) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("branch", branch, "remote", origin);
            config.setString("branch", branch, "merge", "refs/heads/" + branch);

            config.setString("remote", origin, "url", remoteRepository);
            config.setString("remote", origin, "fetch", "+refs/heads/*:refs/remotes/" + origin + "/*");
            try {
                config.save();
            } catch (IOException e) {
                LOG.error("Failed to save the git configuration to " + git.getRepository().getDirectory()
                        + " with branch " + branch + " on " + origin + " remote repo: " + remoteRepository + " due: " + e.getMessage() + ". This exception is ignored.", e);
            }
        }
    }

    public static void addFiles(Git git, File... files) throws GitAPIException, IOException {
        File rootDir = getRootGitDirectory(git);
        for (File file : files) {
            String relativePath = getFilePattern(rootDir, file);
            git.add().addFilepattern(relativePath).call();
        }
    }

    public static String getFilePattern(File rootDir, File file) throws IOException {
        String relativePath = FileUtil.getRelativePath(rootDir, file).getPath();
        if (relativePath.startsWith(File.separator)) {
            relativePath = relativePath.substring(1);
        }
        return relativePath.replace(File.separatorChar, '/');
    }

    public static RevCommit doCommitAndPush(Git git, String message, UserDetails userDetails, PersonIdent author, String branch, String origin, boolean pushOnCommit) throws GitAPIException {
        CommitCommand commit = git.commit().setAll(true).setMessage(message);
        if (author != null) {
            commit = commit.setAuthor(author);
        }

        RevCommit answer = commit.call();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Committed " + answer.getId() + " " + answer.getFullMessage());
        }

        if (pushOnCommit) {
            PushCommand push = git.push();
            configureCommand(push, userDetails);
            Iterable<PushResult> results = push.setRemote(origin).call();
            for (PushResult result : results) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Pushed " + result.getMessages() + " " + result.getURI() + " branch: " + branch + " updates: " + toString(result.getRemoteUpdates()));
                }
            }
        }
        return answer;
    }

    public static void doAddCommitAndPushFiles(Git git, UserDetails userDetails, PersonIdent personIdent, String branch, String origin, String message, boolean pushOnCommit) throws GitAPIException {
        git.add().addFilepattern(".").call();
        doCommitAndPush(git, message, userDetails, personIdent, branch, origin, pushOnCommit);
    }

    /**
     * Retrieves a Java Date from a Git commit.
     *
     * @param commit the commit
     * @return date of the commit or Date(0) if the commit is null
     */
    public static Date getCommitDate(RevCommit commit) {
        if (commit == null) {
            return new Date(0);
        }
        return new Date(commit.getCommitTime() * 1000L);
    }

    /**
     * Configures the transport of the command to deal with things like SSH
     */
    public static <C extends GitCommand> void configureCommand(TransportCommand<C, ?> command, UserDetails userDetails) {
        configureCommand(command, userDetails.createCredentialsProvider(), userDetails.getSshPrivateKey(), userDetails.getSshPublicKey());
        command.setCredentialsProvider(userDetails.createCredentialsProvider());
    }

    /**
     * Configures the transport of the command to deal with things like SSH
     */
    public static <C extends GitCommand> void configureCommand(TransportCommand<C, ?> command, CredentialsProvider credentialsProvider, final File sshPrivateKey, final File sshPublicKey) {
        LOG.info("Using " + credentialsProvider);
        if (sshPrivateKey != null) {
            final CredentialsProvider provider = credentialsProvider;
            command.setTransportConfigCallback(new TransportConfigCallback() {
                @Override
                public void configure(Transport transport) {
                    if (transport instanceof SshTransport) {
                        SshTransport sshTransport = (SshTransport) transport;
                        SshSessionFactory sshSessionFactory = new JschConfigSessionFactory() {
                            @Override
                            protected void configure(OpenSshConfig.Host host, Session session) {
                                session.setConfig("StrictHostKeyChecking", "no");
                                UserInfo userInfo = new CredentialsProviderUserInfo(session, provider);
                                session.setUserInfo(userInfo);
                            }

                            @Override
                            protected JSch createDefaultJSch(FS fs) throws JSchException {
                                JSch jsch = super.createDefaultJSch(fs);
                                jsch.removeAllIdentity();
                                String absolutePath = sshPrivateKey.getAbsolutePath();
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("Adding identity privateKey: " + sshPrivateKey + " publicKey: " + sshPublicKey);
                                }
                                if (sshPublicKey != null) {
                                    jsch.addIdentity(absolutePath, sshPublicKey.getAbsolutePath(), null);
                                } else {
                                    jsch.addIdentity(absolutePath);
                                }
                                return jsch;
                            }
                        };
                        sshTransport.setSshSessionFactory(sshSessionFactory);
                    }
                }
            });
        }
    }

    /**
     * Git tends to ignore empty directories so lets add a dummy file to empty folders to keep them in git
     */
    public static void addDummyFileToEmptyFolders(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children == null || children.length == 0) {
                File dummyFile = new File(dir, ".gitkeep");
                try {
                    FileUtils.write(dummyFile, "This file is only here to avoid git removing empty folders\nOnce there are files in this folder feel free to delete this file!", Charset.defaultCharset());
                } catch (IOException e) {
                    LOG.warn("Failed to write file " + dummyFile + ". " + e, e);
                }
            } else {
                for (File child : children) {
                    if (child.isDirectory()) {
                        addDummyFileToEmptyFolders(child);
                    }
                }
            }
        }
    }

    /**
     * Returns the git repository for the current folder or null if none can be found
     */
    public static Repository findRepository(File baseDir) throws IOException {
        File gitFolder = GitUtil.findGitFolder(baseDir);
        if (gitFolder == null) {
            // No git repository found
            return null;
        }
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder
                .readEnvironment()
                .setGitDir(gitFolder)
                .build();
        return repository;
    }

    /**
     * Returns the ~/.gitconfig file parsed
     */
    public static Map<String, Properties> parseGitConfig() throws IOException {
        String homeDir = System.getProperty("user.home", ".");
        File file = new File(homeDir, ".gitconfig");
        if (file.exists() && file.isFile()) {
            return IniFileUtils.parseIniFile(file);
        } else {
            return new HashMap<>();
        }
    }

    public static String getGitHostName(String gitUrl) {
        try {
            URI uri = new URI(gitUrl);
            return uri.getHost();
        } catch (URISyntaxException e) {
            // ignore
        }
        String[] split = gitUrl.split(":");
        if (split.length > 1) {
            String prefix = split[0];
            int idx = prefix.indexOf('@');
            if (idx >= 0) {
                return prefix.substring(idx + 1);
            } else {
                return prefix;
            }
        }
        return null;
    }

    public static String getGitProtocol(String gitUrl) {
        try {
            URI uri = new URI(gitUrl);
            return uri.getScheme();
        } catch (URISyntaxException e) {
            // ignore
        }
        String[] split = gitUrl.split(":");
        if (split.length > 1) {
            String prefix = split[0];
            int idx = prefix.indexOf('@');
            if (idx >= 0) {
                return "ssh";
            }
        }
        return null;
    }
}