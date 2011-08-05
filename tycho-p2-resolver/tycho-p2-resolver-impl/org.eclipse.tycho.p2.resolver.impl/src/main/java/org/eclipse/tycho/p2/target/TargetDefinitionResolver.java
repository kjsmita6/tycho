/*******************************************************************************
 * Copyright (c) 2011 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.p2.target;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.internal.p2.director.Explanation;
import org.eclipse.equinox.internal.p2.director.Projector;
import org.eclipse.equinox.internal.p2.director.QueryableArray;
import org.eclipse.equinox.internal.p2.director.SimplePlanner;
import org.eclipse.equinox.internal.p2.director.Slicer;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.MetadataFactory.InstallableUnitDescription;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.metadata.VersionRange;
import org.eclipse.equinox.p2.query.IQuery;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.eclipse.osgi.util.NLS;
import org.eclipse.tycho.core.facade.MavenLogger;
import org.eclipse.tycho.p2.target.facade.TargetDefinition;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.InstallableUnitLocation;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Location;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Repository;
import org.eclipse.tycho.p2.target.facade.TargetDefinition.Unit;
import org.eclipse.tycho.p2.target.facade.TargetDefinitionResolutionException;
import org.eclipse.tycho.p2.util.StatusTool;

public class TargetDefinitionResolver {
    private static final IInstallableUnit[] EMPTY_IU_ARRAY = new IInstallableUnit[0];

    private IMetadataRepositoryManager metadataManager;

    private final MavenLogger logger;

    private final List<Map<String, String>> environments;

    public TargetDefinitionResolver(List<Map<String, String>> environments, IProvisioningAgent agent, MavenLogger logger) {
        this.environments = environments;
        this.logger = logger;
        this.metadataManager = (IMetadataRepositoryManager) agent.getService(IMetadataRepositoryManager.SERVICE_NAME);
    }

    public TargetPlatformContent resolveContent(TargetDefinition definition) throws TargetDefinitionResolutionException {

        List<LoadedLocation> loadedLocations = new ArrayList<LoadedLocation>(definition.getLocations().size());
        List<IQueryable<IInstallableUnit>> availableUnits = new ArrayList<IQueryable<IInstallableUnit>>();
        List<IInstallableUnit> seedUnits = new ArrayList<IInstallableUnit>();
        List<URI> artifactRepositories = new ArrayList<URI>();

        for (Location locationDefinition : definition.getLocations()) {
            if (locationDefinition instanceof InstallableUnitLocation) {
                InstallableUnitLocation iuLocationDefinition = (InstallableUnitLocation) locationDefinition;

                LoadedLocation loadedLocation = new LoadedLocation(iuLocationDefinition);
                loadedLocations.add(loadedLocation);
                availableUnits.add(loadedLocation.getAvailableUnits());
                seedUnits.addAll(loadedLocation.getSeedUnits());
                for (Repository repository : iuLocationDefinition.getRepositories()) {
                    artifactRepositories.add(repository.getLocation());
                }
            } else {
                logger.warn(NLS.bind("Target location type: {0} is not supported",
                        locationDefinition.getTypeDescription()));
            }
        }
        Collection<IInstallableUnit> resolvedUnits = resolveWithPlanner(seedUnits,
                QueryUtil.compoundQueryable(availableUnits));
        return new ResolvedDefinition(resolvedUnits, artifactRepositories);
    }

    @SuppressWarnings("restriction")
    private Collection<IInstallableUnit> resolveWithPlanner(List<IInstallableUnit> seedUnits,
            IQueryable<IInstallableUnit> availableUnits) {
        Collection<IInstallableUnit> result = new ArrayList<IInstallableUnit>();

        for (Map<String, String> environment : environments) {
            Map<String, String> selectionContext = SimplePlanner.createSelectionContext(environment);
            Collection<IInstallableUnit> resolvedUnits = resolveForPlatform(seedUnits, availableUnits, selectionContext);
            result.addAll(resolvedUnits);
        }

        return result;
    }

    @SuppressWarnings("restriction")
    private Collection<IInstallableUnit> resolveForPlatform(List<IInstallableUnit> seedUnits,
            IQueryable<IInstallableUnit> availableUnits, Map<String, String> selectionContext) {
        IProgressMonitor monitor = new NullProgressMonitor();
        Slicer slicer = new Slicer(availableUnits, selectionContext, false);
        IQueryable<IInstallableUnit> slice = slicer.slice(seedUnits.toArray(EMPTY_IU_ARRAY), monitor);

        if (slice == null) {
            MultiStatus slicerStatus = slicer.getStatus();
            throw new TargetDefinitionResolutionException(StatusTool.collectProblems(slicerStatus),
                    StatusTool.findException(slicer.getStatus()));
        }

        Projector projector = new Projector(slice, selectionContext, new HashSet<IInstallableUnit>(), false);
        projector.encode(createMetaIU(seedUnits), EMPTY_IU_ARRAY /* alreadyExistingRoots */, new QueryableArray(
                EMPTY_IU_ARRAY) /* installed IUs */, seedUnits /* newRoots */, monitor);
        IStatus s = projector.invokeSolver(monitor);

        if (s.getSeverity() == IStatus.ERROR) {
            Set<Explanation> explanation = projector.getExplanation(monitor);
            throw new TargetDefinitionResolutionException(explanation.toString());
        }
        Collection<IInstallableUnit> resolvedUnits = projector.extractSolution();
        return resolvedUnits;
    }

    private IInstallableUnit createMetaIU(Collection<IInstallableUnit> rootIUs) {
        InstallableUnitDescription iud = new MetadataFactory.InstallableUnitDescription();
        String time = Long.toString(System.currentTimeMillis());
        iud.setId(time);
        iud.setVersion(Version.createOSGi(0, 0, 0, time));

        ArrayList<IRequirement> requirements = new ArrayList<IRequirement>();
        for (IInstallableUnit iu : rootIUs) {
            VersionRange range = new VersionRange(iu.getVersion(), true, iu.getVersion(), true);
            requirements
                    .add(MetadataFactory.createRequirement(IInstallableUnit.NAMESPACE_IU_ID, iu.getId(), range,
                            iu.getFilter(), 1 /* min */, iu.isSingleton() ? 1 : Integer.MAX_VALUE /* max */, true /* greedy */));
        }

        iud.setRequirements(requirements.toArray(new IRequirement[requirements.size()]));
        return MetadataFactory.createInstallableUnit(iud);
    }

    private class LoadedLocation {
        private final InstallableUnitLocation location;
        private IQueryable<IInstallableUnit> repositoryUnits;

        LoadedLocation(InstallableUnitLocation location) {
            this.location = location;
            List<? extends Repository> repositories = location.getRepositories();
            List<IQueryable<IInstallableUnit>> loadedRepositories = new ArrayList<IQueryable<IInstallableUnit>>();
            for (Repository repository : repositories) {
                IMetadataRepository loadedRepository = loadRepository(repository);
                loadedRepositories.add(loadedRepository);
            }
            if (loadedRepositories.size() == 1) {
                repositoryUnits = loadedRepositories.get(0);
            } else {
                repositoryUnits = QueryUtil.compoundQueryable(loadedRepositories);
            }

        }

        private IMetadataRepository loadRepository(Repository repository) {
            try {
                return metadataManager.loadRepository(repository.getLocation(), null);
            } catch (ProvisionException e) {
                throw new TargetDefinitionResolutionException("Failed to load metadata repository from URL "
                        + repository.getLocation(), e);
            }
        }

        IQueryable<IInstallableUnit> getAvailableUnits() {
            return repositoryUnits;
        }

        Set<IInstallableUnit> getSeedUnits() {
            Set<IInstallableUnit> result = new HashSet<IInstallableUnit>();
            for (Unit unit : location.getUnits()) {
                result.add(getUnitInstance(unit));
            }
            return result;
        }

        private IInstallableUnit getUnitInstance(Unit unitReference) {
            IQueryResult<IInstallableUnit> queryResult = searchUnitInThisLocation(unitReference);

            if (queryResult.isEmpty()) {
                throw new TargetDefinitionResolutionException(NLS.bind(
                        "Unit {0}/{1} is not contained in the repositories in the same location",
                        unitReference.getId(), unitReference.getVersion()));
            }
            // if the repository contains the same iu/version twice, both are identical and
            // it is OK to use either 
            IInstallableUnit unitInstance = queryResult.iterator().next();
            return unitInstance;
        }

        private IQueryResult<IInstallableUnit> searchUnitInThisLocation(Unit unitReference) {
            Version version = parseVersion(unitReference);

            // the createIUQuery treats 0.0.0 version as "any version", and all other versions as exact versions
            IQuery<IInstallableUnit> matchingIUQuery = QueryUtil.createIUQuery(unitReference.getId(), version);
            IQuery<IInstallableUnit> latestMatchingIUQuery = QueryUtil.createLatestQuery(matchingIUQuery);

            IQueryResult<IInstallableUnit> queryResult = repositoryUnits.query(latestMatchingIUQuery, null);
            return queryResult;
        }

        private Version parseVersion(Unit unitReference) {
            try {
                return Version.parseVersion(unitReference.getVersion());
            } catch (IllegalArgumentException e) {
                throw new TargetDefinitionResolutionException(NLS.bind("Cannot parse version \"{0}\" of unit \"{1}\"",
                        unitReference.getVersion(), unitReference.getId()), e);
            }
        }
    }

    private static class ResolvedDefinition implements TargetPlatformContent {

        private Collection<? extends IInstallableUnit> units;
        private Collection<URI> artifactRepositories;

        public ResolvedDefinition(Collection<? extends IInstallableUnit> units, Collection<URI> artifactRepositories) {
            this.units = units;
            this.artifactRepositories = artifactRepositories;
        }

        public Collection<? extends IInstallableUnit> getUnits() {
            return units;
        }

        public Collection<URI> getArtifactRepositoryLocations() {
            return artifactRepositories;
        }
    }
}
