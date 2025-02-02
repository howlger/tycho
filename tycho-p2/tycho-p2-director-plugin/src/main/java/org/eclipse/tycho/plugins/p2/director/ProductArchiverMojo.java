/*******************************************************************************
 * Copyright (c) 2010, 2022 SAP SE and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     SAP SE - initial API and implementation
 *     Sonatype Inc. - ongoing development
 *     Christoph Läubrich - Bug 568788 - Support new format .tgz in tycho-p2-director:archive-products
 *******************************************************************************/
package org.eclipse.tycho.plugins.p2.director;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.tar.TarArchiver;
import org.codehaus.plexus.archiver.tar.TarArchiver.TarCompressionMethod;
import org.codehaus.plexus.archiver.tar.TarLongFileMode;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.eclipse.tycho.TargetEnvironment;
import org.eclipse.tycho.plugins.tar.TarGzArchiver;

/**
 * Creates archives with the product installations.
 */
@Mojo(name = "archive-products", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class ProductArchiverMojo extends AbstractProductMojo {
    private static final Object LOCK = new Object();

    private static final String DEFAULT_ARCHIVE_FORMAT = "zip";
    private static final String TAR_GZ_ARCHIVE_FORMAT = "tar.gz";
    private static final String TGZ_ARCHIVE_FORMAT = "tgz";

    private interface ProductArchiver {
        Archiver getArchiver() throws ArchiverException;
    }

    /**
     * Maps archive type to ProductArchiver
     */
    private final Map<String, ProductArchiver> productArchivers;

    /**
     * <p>
     * Maps os to format. By default a zip file will be created.
     * 
     * For example, the following configuration will create tar.gz product archives for Linux
     * 
     * <pre>
     * {@code
     * <formats>
     *   <linux>tar.gz</linux>
     * </formats>
     * }
     * </pre>
     * 
     * Supported formats
     * 
     * <ul>
     * <li>zip</li>
     * <li>tar.gz</li>
     * </ul>
     * </p>
     */
    @Parameter
    private Map<String, String> formats;

    @Component(role = Archiver.class, hint = "zip")
    private Archiver zipArchiver;

    @Component(role = Archiver.class, hint = "tar")
    private TarArchiver tarArchiver;

    @Component
    private MavenProjectHelper helper;

    public ProductArchiverMojo() {
        productArchivers = new HashMap<>();

        productArchivers.put("zip", () -> zipArchiver);

        ProductArchiver tarGzArchiver = () -> {
            tarArchiver.setCompression(TarCompressionMethod.gzip);
            // avoid lots of long file path (> 100 chars) warnings
            tarArchiver.setLongfile(TarLongFileMode.gnu);
            return tarArchiver;
        };
        productArchivers.put(TAR_GZ_ARCHIVE_FORMAT, tarGzArchiver);
        productArchivers.put(TGZ_ARCHIVE_FORMAT, tarGzArchiver);

    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        synchronized (LOCK) {
            ProductConfig config = getProductConfig();
            if (!config.uniqueAttachIds()) {
                throw new MojoFailureException("Artifact file names for the archived products are not unique. "
                        + "Configure the attachId or select a subset of products. Current configuration: "
                        + config.getProducts());
            }

            for (Product product : config.getProducts()) {
                File bundlePool = getProductBundlePoolDirectory(product);
                if (bundlePool != null) {
                    materialize(product, null);
                } else {
                    for (TargetEnvironment env : getEnvironments()) {
                        materialize(product, env);
                    }
                }
            }
        }
    }

    private void materialize(Product product, TargetEnvironment env) throws MojoExecutionException {
        String format = getArchiveFormat(product, env);
        ProductArchiver productArchiver = productArchivers.get(format);
        if (productArchiver == null) {
            String os = env != null ? "os=" + env.getOs() : "";
            throw new MojoExecutionException("Unknown or unsupported archive format " + os + " format=" + format);
        }

        File productArchive = new File(getProductsBuildDirectory(),
                getArchiveFileName(product) + "-" + getOsWsArch(env, '.') + "." + format);

        try {
            final File sourceDir = getProductMaterializeDirectory(product, env);
            if ((TGZ_ARCHIVE_FORMAT.equals(format) || TAR_GZ_ARCHIVE_FORMAT.equals(format))
                    && !"plexus".equals(getSession().getUserProperties().getProperty("tycho.tar"))) {
                getLog().debug("Using commons-compress tar");
                createCommonsCompressTarGz(productArchive, sourceDir);
            } else {
                Archiver archiver = productArchiver.getArchiver();
                archiver.setDestFile(productArchive);
                DefaultFileSet fileSet = new DefaultFileSet(sourceDir);
                fileSet.setUsingDefaultExcludes(false);
                archiver.addFileSet(fileSet);
                archiver.createArchive();
            }
        } catch (ArchiverException | IOException e) {
            throw new MojoExecutionException("Error packing product", e);
        }

        final String artifactClassifier = getArtifactClassifier(product, env);
        helper.attachArtifact(getProject(), format, artifactClassifier, productArchive);
    }

    private void createCommonsCompressTarGz(File productArchive, File sourceDir) throws IOException {
        TarGzArchiver archiver = new TarGzArchiver();
        archiver.setLog(getLog());
        archiver.addDirectory(sourceDir);
        archiver.setDestFile(productArchive);
        archiver.createArchive();
    }

    private String getArchiveFormat(Product product, TargetEnvironment env) {
        String format = DEFAULT_ARCHIVE_FORMAT;
        if (formats != null) {
            if (product.isMultiPlatformPackage()) {
                format = formats.get("multiPlatformPackage");
            } else {
                format = formats.get(env.getOs());
            }
        }
        if (format != null) {
            format = format.trim();
        }
        if (format == null || format.isEmpty()) {
            format = DEFAULT_ARCHIVE_FORMAT;
        }
        return format;
    }

    static String getArchiveFileName(Product product) {
        if (product.getArchiveFileName() != null) {
            return product.getArchiveFileName();
        } else {
            return product.getId();
        }
    }

    static String getArtifactClassifier(Product product, TargetEnvironment environment) {
        // classifier (and hence artifact file name) ends with os.ws.arch (similar to Eclipse
        // download packages)
        final String artifactClassifier;
        if (product.getAttachId() == null) {
            artifactClassifier = getOsWsArch(environment, '.');
        } else {
            artifactClassifier = product.getAttachId() + "-" + getOsWsArch(environment, '.');
        }
        return artifactClassifier;
    }
}
