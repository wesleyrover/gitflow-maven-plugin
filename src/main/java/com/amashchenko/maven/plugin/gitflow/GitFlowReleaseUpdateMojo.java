/*
 * Copyright 2014-2018 Aleksandr Mashchenko.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amashchenko.maven.plugin.gitflow;

import java.util.HashMap;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.StringUtils;

@Mojo(name = "release-update", aggregator = true)
public class GitFlowReleaseUpdateMojo extends AbstractGitFlowMojo {

    /** Whether to skip tagging the release in Git. */
    @Parameter(property = "skipTag", defaultValue = "false")
    private boolean skipTag = false;

    /**
     * Whether to allow SNAPSHOT versions in dependencies.
     * 
     */
    @Parameter(property = "allowSnapshots", defaultValue = "false")
    private boolean allowSnapshots = false;

    /**
     * Whether to push to the remote.
     * 
     */
    @Parameter(property = "pushRemote", defaultValue = "true")
    private boolean pushRemote;

    /**
     * Release version to use instead of the default next release version in
     * non-interactive mode.
     * 
     */
    @Parameter(property = "releaseVersion", defaultValue = "")
    private String releaseVersion = "";

    /**
     * Whether to remove qualifiers from the next release version. Has effect only
     * if {@link #incrementVersion} is set to <code>true</code>.
     * 
     */
    @Parameter(property = "digitsOnlyVersion", defaultValue = "false")
    private boolean digitsOnlyVersion = false;

    /**
     * Which digit to increment in the next release version. Starts from zero. Has
     * effect only if {@link #incrementVersion} is set to <code>true</code>.
     * 
     */
    @Parameter(property = "versionDigitToIncrement")
    private Integer versionDigitToIncrement;

    /**
     * Whether to increment version number.
     * 
     */
    @Parameter(property = "incrementVersion", defaultValue = "false")
    private boolean incrementVersion = false;

    /**
     * Whether to make a GPG-signed tag.
     * 
     */
    @Parameter(property = "gpgSignTag", defaultValue = "false")
    private boolean gpgSignTag = false;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            // check uncommitted changes
            checkUncommittedChanges();

            // git for-each-ref --format='%(refname:short)' refs/heads/release/*
            final String releaseBranch = gitFindBranches(gitFlowConfig.getReleaseBranchPrefix(), false).trim();

            if (StringUtils.isBlank(releaseBranch)) {
                throw new MojoFailureException("There is no release branch.");
            } else if (StringUtils.countMatches(releaseBranch, gitFlowConfig.getReleaseBranchPrefix()) > 1) {
                throw new MojoFailureException("More than one release branch exists. Cannot update release.");
            }

            gitCheckout(releaseBranch);

            // check snapshots dependencies
            if (!allowSnapshots) {
                checkSnapshotDependencies();
            }

            if (fetchRemote) {
                // fetch and check remote
                gitFetchRemoteAndCompare(releaseBranch);
            }

            // get current project version from pom
            final String currentVersion = getCurrentProjectVersion();
            String version = currentVersion;

            if (!settings.isInteractiveMode() && StringUtils.isNotBlank(releaseVersion)) {
                version = releaseVersion;
            } else if (incrementVersion) {
                GitFlowVersionInfo versionInfo = new GitFlowVersionInfo(version);

                if (digitsOnlyVersion) {
                    versionInfo = versionInfo.digitsVersionInfo();
                }

                version = versionInfo.nextReleaseVersion(versionDigitToIncrement);
            }

            if (StringUtils.isBlank(version)) {
                throw new MojoFailureException("Next release version is blank.");
            }

            if (!currentVersion.equals(version)) {
                mvnSetVersions(version);

                Map<String, String> properties = new HashMap<String, String>();
                properties.put("version", version);

                gitCommit(commitMessages.getReleaseStartMessage(), properties);
            }

            if (!skipTag) {
                String tagVersion = version;
                if (tychoBuild && ArtifactUtils.isSnapshot(version)) {
                    tagVersion = version.replace("-" + Artifact.SNAPSHOT_VERSION, "");
                }

                // git tag -a ...
                gitTag(gitFlowConfig.getVersionTagPrefix() + tagVersion, commitMessages.getTagReleaseMessage(),
                        gpgSignTag);
            }

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }

            if (pushRemote) {
                gitPush(releaseBranch, !skipTag);
            }
        } catch (Exception e) {
            throw new MojoFailureException("release-update", e);
        }
    }
}
