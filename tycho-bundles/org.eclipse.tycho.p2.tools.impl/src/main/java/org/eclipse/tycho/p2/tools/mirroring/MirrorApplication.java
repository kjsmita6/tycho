/*******************************************************************************
 * Copyright (c) 2010, 2011 SAP SE and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     SAP SE - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.p2.tools.mirroring;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.equinox.internal.p2.director.PermissiveSlicer;
import org.eclipse.equinox.internal.p2.director.Slicer;
import org.eclipse.equinox.internal.p2.metadata.IRequiredCapability;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.internal.repository.tools.RepositoryDescriptor;
import org.eclipse.equinox.p2.internal.repository.tools.SlicingOptions;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.expression.IMatchExpression;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.IRepository;
import org.eclipse.equinox.p2.repository.IRepositoryReference;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.tycho.p2.tools.RepositoryReference;

@SuppressWarnings("restriction")
public class MirrorApplication extends org.eclipse.equinox.p2.internal.repository.tools.MirrorApplication {

    private final Map<String, String> extraArtifactRepositoryProperties;
    private final List<RepositoryReference> repositoryReferences;

    public MirrorApplication(IProvisioningAgent agent, Map<String, String> extraArtifactRepositoryProperties,
            List<RepositoryReference> repositoryReferences) {
        super();
        this.agent = agent;
        this.extraArtifactRepositoryProperties = extraArtifactRepositoryProperties;
        this.repositoryReferences = repositoryReferences;
        this.removeAddedRepositories = false;
    }

    @Override
    protected IArtifactRepository initializeDestination(RepositoryDescriptor toInit, IArtifactRepositoryManager mgr)
            throws ProvisionException {
        IArtifactRepository result = super.initializeDestination(toInit, mgr);
        // simple.SimpleArtifactRepository.PUBLISH_PACK_FILES_AS_SIBLINGS is not public
        result.setProperty("publishPackFilesAsSiblings", "true");
        extraArtifactRepositoryProperties.entrySet()
                .forEach(entry -> result.setProperty(entry.getKey(), entry.getValue()));
        return result;
    }

    @Override
    protected Slicer createSlicer(SlicingOptions options) {
        Map<String, String> context = options.getFilter();
        boolean includeOptionalDependencies = options.includeOptionalDependencies();
        boolean onlyFilteredRequirements = options.followOnlyFilteredRequirements();
        boolean considerFilter = (context != null && context.size() > 1) ? true : false;
        PermissiveSlicer slicer = new PermissiveSlicer(getCompositeMetadataRepository(), context,
                includeOptionalDependencies, options.isEverythingGreedy(), options.forceFilterTo(),
                options.considerStrictDependencyOnly(), onlyFilteredRequirements) {
            @Override
            protected boolean isApplicable(IInstallableUnit iu, IRequirement req) {
                if (QueryUtil.isGroup(iu)) {
                    if (req instanceof IRequiredCapability) {
                        if (IInstallableUnit.NAMESPACE_IU_ID.equals(((IRequiredCapability) req).getNamespace())) {
                            if (!includeOptionalDependencies) {
                                if (req.getMin() == 0) {
                                    return false;
                                }
                            }
                            IMatchExpression<IInstallableUnit> filter = req.getFilter();
                            if (considerFilter) {
                                if (onlyFilteredRequirements && filter == null) {
                                    return false;
                                }
                                return filter == null || filter.isMatch(selectionContext);
                            }
                            if (filter == null && onlyFilteredRequirements) {
                                return false;
                            }
                            return true;
                        }
                    }
                }
                return super.isApplicable(iu, req);
            }

        };
        return slicer;
    }

    @Override
    protected IMetadataRepository initializeDestination(RepositoryDescriptor toInit, IMetadataRepositoryManager mgr)
            throws ProvisionException {
        IMetadataRepository result = super.initializeDestination(toInit, mgr);
        List<? extends IRepositoryReference> iRepoRefs = repositoryReferences.stream()
                .flatMap(MirrorApplication::toSpiRepositoryReferences).collect(Collectors.toList());
        result.addReferences(iRepoRefs);
        return result;
    }

    private static Stream<org.eclipse.equinox.p2.repository.spi.RepositoryReference> toSpiRepositoryReferences(
            RepositoryReference rr) {
        return Stream.of(toSpiRepositoryReference(rr, IRepository.TYPE_METADATA),
                toSpiRepositoryReference(rr, IRepository.TYPE_ARTIFACT));
    }

    private static org.eclipse.equinox.p2.repository.spi.RepositoryReference toSpiRepositoryReference(
            RepositoryReference rr, int type) {
        return new org.eclipse.equinox.p2.repository.spi.RepositoryReference(URI.create(rr.getLocation()), rr.getName(),
                type, rr.isEnable() ? IRepository.ENABLED : IRepository.NONE);
    }

}
