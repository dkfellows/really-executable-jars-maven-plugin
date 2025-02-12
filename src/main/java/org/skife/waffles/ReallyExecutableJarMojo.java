/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.skife.waffles;

import net.e175.klaus.zip.ZipPrefixer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.IOUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;

/**
 * Make an artifact generated by the build really executable. The resulting artifact
 * can be run directly from the command line (Java must be installed and in the
 * shell path).
 */
@Mojo(name = "really-executable-jar",
        threadSafe = true,
        defaultPhase = LifecyclePhase.PACKAGE)
public class ReallyExecutableJarMojo extends AbstractMojo {
    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Specific file to make executable instead of default artifact(s).
     */
    @Parameter(property = "really-executable-jar.inputFile")
    private File inputFile = null;

    /**
     * Java command line arguments to embed. Only used with the default stanza.
     */
    @Parameter(property = "really-executable-jar.flags")
    private String flags = "";

    /**
     * Name of the generated binary. This does not work with multiple artifacts.
     */
    @Parameter(property = "really-executable-jar.programFile")
    private String programFile = null;

    /**
     * If set, only artifacts with this classifier are made executable.
     */
    @Parameter(property = "really-executable-jar.classifier")
    private String classifier;

    /**
     * Allow other packaging types than "jar".
     */
    @Parameter(defaultValue = "false", property = "really-executable-jar.allowOtherTypes")
    private boolean allowOtherTypes;

    /**
     * Attach the binary as an artifact to the deploy (if programFile is set).
     */
    @Parameter(defaultValue = "false", property = "really-executable-jar.attachProgramFile")
    private boolean attachProgramFile = false;

    /**
     * File ending of the program artifact to attach (if programFile and attachProgramFile are set).
     */
    @Parameter(defaultValue = "sh", property = "really-executable-jar.programFileType")
    private String programFileType = "sh";

    /**
     * Shell script to add to the jar instead of the default stanza.
     */
    @Parameter(property = "really-executable-jar.scriptFile")
    private String scriptFile = null;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<File> files = new ArrayList<>();

            if (inputFile != null) {
                if (inputFile.exists()) {
                    files.add(inputFile);
                } else {
                    throw new MojoExecutionException("Unable to find " + inputFile);
                }
            } else {
                if (shouldProcess(project.getArtifact())) {
                    files.add(project.getArtifact().getFile());
                }

                for (Artifact item : project.getAttachedArtifacts()) {
                    if (shouldProcess(item)) {
                        files.add(item.getFile());
                    }
                }
            }

            if (files.isEmpty()) {
                throw new MojoExecutionException("Could not find any jars to make executable");
            }

            if (programFile != null && !programFile.matches("\\s+")) {  // Java 11+: isBlank()
                if (files.size() > 1) {
                    throw new MojoExecutionException("programFile set, but multiple candidate artifacts found: " + files);
                }

                File file = files.get(0);
                File dir = file.getParentFile();
                File exec = new File(dir, programFile);
                Files.copy(file.toPath(), exec.toPath());
                makeExecutable(exec);
                if (attachProgramFile) {
                    projectHelper.attachArtifact(project, programFileType, exec);
                }
            } else {
                for (File file : files) {
                    makeExecutable(file);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private boolean shouldProcess(Artifact artifact) {
        getLog().debug("Considering " + artifact);
        if (artifact == null) {
            return false;
        }

        if (!allowOtherTypes && !artifact.getType().equals("jar")) {
            return false;
        }

        return classifier == null || classifier.equals(artifact.getClassifier());
    }

    private void makeExecutable(File file)
            throws MojoExecutionException {
        assert file != null;

        getLog().debug("Making " + file.getAbsolutePath() + " executable");

        Path target = file.toPath();
        try {
            ZipPrefixer.applyPrefixBytesToZip(target,
                    Arrays.asList(getPreamble(target.toUri()), "\n\n".getBytes(UTF_8)));
        } catch (IOException e) {
            throw new MojoExecutionException(format("Failed to apply prefix to JAR [%s]", file.getAbsolutePath()), e);
        }

        if (!file.setExecutable(true, false)) {
            throw new MojoExecutionException(format("Could not make JAR [%s] executable", file.getAbsolutePath()));
        }
        getLog().info(format("Successfully made JAR [%s] executable", file.getAbsolutePath()));
    }

    private byte[] getPreamble(URI uri) throws MojoExecutionException {
        try {
            if (scriptFile == null) {
                return ("#!/bin/sh\n\nexec java " + flags + " -jar \"$0\" \"$@\"").getBytes(UTF_8);
            }

            if (Files.isReadable(Paths.get(scriptFile))) {
                return readAllBytes(Paths.get(scriptFile));
            }

            try (URLClassLoader loader = new URLClassLoader(new URL[]{uri.toURL()}, null);
                 InputStream scriptIn = loader.getResourceAsStream(scriptFile)) {
                if (scriptIn == null) {
                    throw new IOException("unable to load " + scriptFile);
                }
                return IOUtil.toByteArray(scriptIn); // Java 9+: scriptIn.readAllBytes();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("unable to load preamble data", e);
        }
    }
}
