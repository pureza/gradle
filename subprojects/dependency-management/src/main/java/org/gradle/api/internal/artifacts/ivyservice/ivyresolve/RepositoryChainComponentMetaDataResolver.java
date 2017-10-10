/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.artifacts.ivyservice.ivyresolve;

import org.gradle.api.Transformer;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.internal.artifacts.repositories.resolver.MetadataFetchingCost;
import org.gradle.internal.component.external.model.ModuleComponentResolveMetadata;
import org.gradle.internal.component.model.ComponentOverrideMetadata;
import org.gradle.internal.resolve.ModuleVersionResolveException;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.gradle.internal.resolve.result.BuildableModuleComponentMetaDataResolveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

public class RepositoryChainComponentMetaDataResolver implements ComponentMetaDataResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryChainComponentMetaDataResolver.class);

    private final List<ModuleComponentRepository> repositories = new ArrayList<ModuleComponentRepository>();
    private final List<String> repositoryNames = new ArrayList<String>();
    private final VersionedComponentChooser versionedComponentChooser;
    private final Transformer<ModuleComponentResolveMetadata, RepositoryChainModuleResolution> metaDataFactory;

    public RepositoryChainComponentMetaDataResolver(VersionedComponentChooser componentChooser, Transformer<ModuleComponentResolveMetadata, RepositoryChainModuleResolution> metaDataFactory) {
        this.versionedComponentChooser = componentChooser;
        this.metaDataFactory = metaDataFactory;
    }

    public void add(ModuleComponentRepository repository) {
        repositories.add(repository);
        repositoryNames.add(repository.getName());
    }

    public void resolve(ComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        if (!(identifier instanceof ModuleComponentIdentifier)) {
            throw new UnsupportedOperationException("Can resolve meta-data for module components only.");
        }

        resolveModule((ModuleComponentIdentifier) identifier, componentOverrideMetadata, result);
    }

    @Override
    public boolean isFetchingMetadataCheap(ComponentIdentifier identifier) {
        if (identifier instanceof ModuleComponentIdentifier) {
            for (ModuleComponentRepository repository : repositories) {
                ModuleComponentRepositoryAccess localAccess = repository.getLocalAccess();
                MetadataFetchingCost fetchingCost = localAccess.estimateMetadataFetchingCost((ModuleComponentIdentifier) identifier);
                if (fetchingCost.isFast()) {
                    return true;
                } else if (fetchingCost.isExpensive()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void resolveModule(ModuleComponentIdentifier identifier, ComponentOverrideMetadata componentOverrideMetadata, BuildableComponentResolveResult result) {
        LOGGER.debug("Attempting to resolve component for {} using repositories {}", identifier, repositoryNames);

        List<ComponentMetaDataResolveState> resolveStates = new ArrayList<ComponentMetaDataResolveState>();
        for (ModuleComponentRepository repository : repositories) {
            resolveStates.add(new ComponentMetaDataResolveState(identifier, componentOverrideMetadata, repository, versionedComponentChooser));
        }

        try {
            final RepositoryChainModuleResolution latestResolved = findBestMatch(resolveStates);
            if (latestResolved == null) {
                for (ComponentMetaDataResolveState resolveState : resolveStates) {
                    resolveState.applyTo(result);
                }
                result.notFound(identifier);
            } else {
                LOGGER.debug("Using {} from {}", latestResolved.module.getId(), latestResolved.repository);
                result.resolved(metaDataFactory.transform(latestResolved));
            }
        } catch (Throwable error) {
            result.failed(new ModuleVersionResolveException(identifier, error));
        }
    }

    private RepositoryChainModuleResolution findBestMatch(List<ComponentMetaDataResolveState> resolveStates) throws Throwable {
        LinkedList<ComponentMetaDataResolveState> queue = new LinkedList<ComponentMetaDataResolveState>();
        queue.addAll(resolveStates);

        LinkedList<ComponentMetaDataResolveState> missing = new LinkedList<ComponentMetaDataResolveState>();

        // A first pass to do local resolves only
        RepositoryChainModuleResolution best = findBestMatch(queue, missing);
        if (best != null) {
            return best;
        }

        // Nothing found locally - try a remote search for all resolve states that were not yet searched remotely
        queue.addAll(missing);
        missing.clear();
        return findBestMatch(queue, missing);
    }

    private RepositoryChainModuleResolution findBestMatch(LinkedList<ComponentMetaDataResolveState> queue, Collection<ComponentMetaDataResolveState> missing) throws Throwable {
        RepositoryChainModuleResolution best = null;
        while (!queue.isEmpty()) {
            ComponentMetaDataResolveState request = queue.removeFirst();
            BuildableModuleComponentMetaDataResolveResult metaDataResolveResult;
            metaDataResolveResult = request.resolve();
            switch (metaDataResolveResult.getState()) {
                case Failed:
                    throw metaDataResolveResult.getFailure();
                case Missing:
                    // Queue this up for checking again later
                    if (request.canMakeFurtherAttempts()) {
                        missing.add(request);
                    }
                    break;
                case Resolved:
                    RepositoryChainModuleResolution moduleResolution = new RepositoryChainModuleResolution(request.repository, metaDataResolveResult.getMetaData());
                    if (!metaDataResolveResult.getMetaData().isMissing()) {
                        return moduleResolution;
                    }
                    best = best != null ? best : moduleResolution;
                    break;
                default:
                    throw new IllegalStateException("Unexpected state for resolution: " + metaDataResolveResult.getState());
            }
        }

        return best;
    }
}
