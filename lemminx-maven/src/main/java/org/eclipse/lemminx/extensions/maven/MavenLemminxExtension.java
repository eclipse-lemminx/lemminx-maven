/*******************************************************************************
 * Copyright (c) 2020, 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.PARENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROJECT_ELT;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.maven.Maven;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.internal.aether.DefaultRepositorySystemSessionFactory;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.project.MavenProject;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.codehaus.plexus.ContainerConfiguration;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.classworlds.realm.NoSuchRealmException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.commons.progress.ProgressMonitor;
import org.eclipse.lemminx.commons.progress.ProgressSupport;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.maven.participants.codeaction.ExtractPropertyCodeAction;
import org.eclipse.lemminx.extensions.maven.participants.codeaction.InlinePropertyCodeAction;
import org.eclipse.lemminx.extensions.maven.participants.codeaction.MavenIdPartRemovalCodeAction;
import org.eclipse.lemminx.extensions.maven.participants.codeaction.MavenManagedVersionRemovalCodeAction;
import org.eclipse.lemminx.extensions.maven.participants.codeaction.MavenNoGrammarConstraintsCodeAction;
import org.eclipse.lemminx.extensions.maven.participants.completion.MavenCompletionParticipant;
import org.eclipse.lemminx.extensions.maven.participants.definition.MavenDefinitionParticipant;
import org.eclipse.lemminx.extensions.maven.participants.diagnostics.MavenDiagnosticParticipant;
import org.eclipse.lemminx.extensions.maven.participants.hover.MavenHoverParticipant;
import org.eclipse.lemminx.extensions.maven.participants.rename.MavenPropertyRenameParticipant;
import org.eclipse.lemminx.extensions.maven.project.IMavenProjectBuildListener;
import org.eclipse.lemminx.extensions.maven.project.LoadedMavenProject;
import org.eclipse.lemminx.extensions.maven.project.MavenProjectCache;
import org.eclipse.lemminx.extensions.maven.searcher.LocalRepositorySearcher;
import org.eclipse.lemminx.extensions.maven.searcher.RemoteCentralRepositorySearcher;
import org.eclipse.lemminx.extensions.maven.settings.XMLMavenGeneralSettings;
import org.eclipse.lemminx.extensions.maven.settings.XMLMavenSettings;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.LocalRepositoryUtils;
import org.eclipse.lemminx.extensions.maven.utils.MavenParseUtils;
import org.eclipse.lemminx.services.IXMLDocumentProvider;
import org.eclipse.lemminx.services.IXMLValidationService;
import org.eclipse.lemminx.services.extensions.IXMLExtension;
import org.eclipse.lemminx.services.extensions.XMLExtensionsRegistry;
import org.eclipse.lemminx.services.extensions.codeaction.ICodeActionParticipant;
import org.eclipse.lemminx.services.extensions.completion.ICompletionParticipant;
import org.eclipse.lemminx.services.extensions.diagnostics.IDiagnosticsParticipant;
import org.eclipse.lemminx.services.extensions.hover.IHoverParticipant;
import org.eclipse.lemminx.services.extensions.rename.IRenameParticipant;
import org.eclipse.lemminx.services.extensions.save.ISaveContext;
import org.eclipse.lemminx.services.extensions.save.ISaveContext.SaveContextType;
import org.eclipse.lemminx.settings.AllXMLSettings;
import org.eclipse.lemminx.settings.InitializationOptionsSettings;
import org.eclipse.lemminx.uriresolver.URIResolverExtensionManager;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;

/**
 * Extension for pom.xml.
 *
 */
public class MavenLemminxExtension implements IXMLExtension, IMavenProjectBuildListener {

	// Used for tests
	private static boolean unitTestMode = false;

	private static final Logger LOGGER = Logger.getLogger(MavenLemminxExtension.class.getName());
	private static final String MAVEN_XMLLS_EXTENSION_REALM_ID = MavenLemminxExtension.class.getName();
	private static final long WAIT_SAFE_TIMEOUT_SECONDS = 10;

	private XMLExtensionsRegistry currentRegistry;
	private MavenLemminxWorkspaceReader workspaceReader = new MavenLemminxWorkspaceReader();

	private ICompletionParticipant completionParticipant;
	private IDiagnosticsParticipant diagnosticParticipant;
	private IHoverParticipant hoverParticipant;
	private MavenDefinitionParticipant definitionParticipant;
	private MavenWorkspaceService workspaceServiceParticipant;
	private List<ICodeActionParticipant> codeActionParticipants = new ArrayList<>();
	private IRenameParticipant propertyRenameParticipant;

	private MavenProjectCache cache;
	private RemoteCentralRepositorySearcher centralSearcher;
	private LocalRepositorySearcher localRepositorySearcher;
	private List<IMavenProjectBuildListener> mavenProjectBuildListeners = new ArrayList<>();
	private MavenExecutionRequest mavenRequest;
	private MavenPluginManager mavenPluginManager;
	private PlexusContainer container;
	private MavenSession mavenSession;
	private BuildPluginManager buildPluginManager;

	XMLMavenSettings settings = new XMLMavenSettings();
	private URIResolverExtensionManager resolverExtensionManager;
	private List<WorkspaceFolder> initialWorkspaceFolders = List.of();
	private LinkedHashSet<URI> currentWorkspaceFolders = new LinkedHashSet<>();

	// Thread which loads Maven component (plexus container, maven session, etc)
	// which can take some time.
	private CompletableFuture<Void> mavenInitializer;
	private IXMLDocumentProvider documentProvider;
	private IXMLValidationService validationService;

	private ProgressSupport progressSupport;

	@Override
	public void doSave(ISaveContext context) {
		if (context.getType() == SaveContextType.SETTINGS) {
			final XMLExtensionsRegistry registry = this.currentRegistry; // keep ref as this.currentRegistry becomes
																			// null on stop
			XMLMavenGeneralSettings generalXMLSettings = XMLMavenGeneralSettings
					.getGeneralXMLSettings(context.getSettings());
			if (generalXMLSettings == null) {
				return;
			}
			XMLMavenSettings newSettings = generalXMLSettings.getMaven();
			if (newSettings != null && !Objects.equals(this.settings, newSettings)) {
				stop(registry);
				this.settings = newSettings;
				start(null, registry);
			}
		}
	}

	@Override
	public void start(InitializeParams params, XMLExtensionsRegistry registry) {
		if (params != null) {
			this.initialWorkspaceFolders = params.getWorkspaceFolders();
			Object initOptions = InitializationOptionsSettings.getSettings(params);
			Object xmlSettings = AllXMLSettings.getAllXMLSettings(initOptions);
			XMLMavenGeneralSettings generalXmlSettings = XMLMavenGeneralSettings.getGeneralXMLSettings(xmlSettings);
			if (generalXmlSettings != null) {
				settings = generalXmlSettings.getMaven();
			}
		}
		this.currentRegistry = registry;
		this.resolverExtensionManager = registry.getResolverExtensionManager();
		this.progressSupport = registry.getProgressSupport();
		this.documentProvider = registry.getDocumentProvider();
		this.validationService = registry.getValidationService();
		try {
			// Do not invoke getters the MavenLemminxExtension in participant constructors,
			// or that will trigger loading of plexus, Maven and so on even for non pom
			// files
			// Initialization will happen when calling getters.
			workspaceServiceParticipant = new MavenWorkspaceService(this);
			registry.registerWorkspaceServiceParticipant(workspaceServiceParticipant);
			completionParticipant = new MavenCompletionParticipant(this);
			registry.registerCompletionParticipant(completionParticipant);
			diagnosticParticipant = new MavenDiagnosticParticipant(this);
			registry.registerDiagnosticsParticipant(diagnosticParticipant);
			hoverParticipant = new MavenHoverParticipant(this);
			registry.registerHoverParticipant(hoverParticipant);
			definitionParticipant = new MavenDefinitionParticipant(this);
			registry.registerDefinitionParticipant(definitionParticipant);
			registerCodeActionParticipants(registry);
			propertyRenameParticipant = new MavenPropertyRenameParticipant(this);
			registry.registerRenameParticipant(propertyRenameParticipant);
		} catch (Exception ex) {
			LOGGER.log(Level.SEVERE, ex.getCause().toString(), ex);
		}
	}

	private void initialize() throws MavenInitializationException {
		CompletableFuture<Void> future = getMavenInitializer();
		if (!getMavenInitializer().isDone()) {
			// The Maven initialization is not ready, throws a MavenInitializationException.
			throw new MavenInitializationException(future);
		}
	}

	private CompletableFuture<Void> getMavenInitializer() {
		if (mavenInitializer != null && !mavenInitializer.isCompletedExceptionally()) {
			return mavenInitializer;
		}
		return getOrCreateMavenInitializer();
	}

	private synchronized CompletableFuture<Void> getOrCreateMavenInitializer() {
		if (mavenInitializer != null) {
			return mavenInitializer;
		}
		// Create thread which loads Maven component (plexus container, maven session,
		// etc) which can take some time.
		// We do this initialization on background to avoid breaking the XML syntax
		// validation, XML based onXSD, XML completion based on XSD
		// while Maven component is initializing.
		if (isUnitTestMode()) {
			mavenInitializer = new CompletableFuture<>();
			doInitialize(() -> {
			});
			mavenInitializer.complete(null);
		} else
			mavenInitializer = CompletableFutures.computeAsync(cancelChecker -> {
				doInitialize(cancelChecker);
				return null;
			});
		// Start Maven Project Cache
		mavenInitializer.thenAccept(t -> cache.start());
		return mavenInitializer;
	}

	private void doInitialize(CancelChecker cancelChecker) {
		Exception error = null;
		ProgressMonitor progressMonitor = progressSupport != null ? progressSupport.createProgressMonitor() : null;
		try {
			if (progressMonitor != null) {
				progressMonitor.begin("Loading Maven components...", "", 100, null);
			}
			boolean skipCentralRepository = settings.getCentral().isSkip();
			int nbSteps = 7 - (skipCentralRepository ? 1 : 0);
			int currentStep = 1;
			int percentage = 15;

			// Step1 : initialize Plexus container
			cancelChecker.checkCanceled();
			if (progressMonitor != null) {
				progressMonitor.report("Initializing Plexus container" + getStepMessage(currentStep, nbSteps) + "...",
						percentage, null);
			}
			this.container = newPlexusContainer();

			// Step2 : initialize Maven request
			cancelChecker.checkCanceled();
			if (progressMonitor != null) {
				currentStep++;
				percentage += 15;
				progressMonitor.report("Initializing Maven request" + getStepMessage(currentStep, nbSteps) + "...",
						percentage, null);
			}
			// Get the real local repository of the user
			// Initialize maven request
			mavenRequest = initMavenRequest(container, settings);
			List<File> localRepositoryDirs = LocalRepositoryUtils.getLocalRepositoryPaths(mavenRequest);
			// Step3 : initialize Repository system session
			cancelChecker.checkCanceled();
			if (progressMonitor != null) {
				currentStep++;
				percentage += 15;
				progressMonitor.report(
						"Initializing Repository system session" + getStepMessage(currentStep, nbSteps) + "...",
						percentage, null);
			}

			DefaultRepositorySystemSessionFactory repositorySessionFactory = container
					.lookup(DefaultRepositorySystemSessionFactory.class);
			RepositorySystemSession repositorySystemSession = repositorySessionFactory
					.newRepositorySession(mavenRequest);

			// Step4 : initialize Maven session
			cancelChecker.checkCanceled();
			if (progressMonitor != null) {
				currentStep++;
				percentage += 15;
				progressMonitor.report("Initializing Maven session" + getStepMessage(currentStep, nbSteps) + "...",
						percentage, null);
			}
			MavenExecutionResult mavenResult = new DefaultMavenExecutionResult();
			// TODO: MavenSession is deprecated. Investigate for alternative
			mavenSession = new MavenSession(container, repositorySystemSession, mavenRequest, mavenResult);
			cache = new MavenProjectCache(this, mavenSession, documentProvider);

			// Step5 : create local repository searcher
			cancelChecker.checkCanceled();
			if (progressMonitor != null) {
				currentStep++;
				percentage += 15;
				progressMonitor.report(
						"Creating local repository searcher" + getStepMessage(currentStep, nbSteps) + "...", percentage,
						null);
			}
			Set<File> dirs = new HashSet<>(localRepositoryDirs);
			dirs.add(mavenRequest.getLocalRepositoryPath());
			localRepositorySearcher = new LocalRepositorySearcher(dirs, progressSupport);
			mavenProjectBuildListeners.add(localRepositorySearcher);
			
			if (!skipCentralRepository) {
				// Step6 : create central repository searcher
				cancelChecker.checkCanceled();
				if (progressMonitor != null) {
					currentStep++;
					percentage += 15;
					progressMonitor.report(
							"Creating central repository searcher" + getStepMessage(currentStep, nbSteps) + "...",
							percentage, null);
				}
				centralSearcher = new RemoteCentralRepositorySearcher();
			}
			buildPluginManager = null;
			mavenPluginManager = container.lookup(MavenPluginManager.class);
			buildPluginManager = container.lookup(BuildPluginManager.class);

			// Step7 : initializing Workspace readers
			cancelChecker.checkCanceled();
			if (progressMonitor != null) {
				currentStep++;
				percentage += 15;
				progressMonitor.report("Initializing Workspace readers" + getStepMessage(currentStep, nbSteps) + "...",
						percentage, null);
			}
			internalDidChangeWorkspaceFolders(this.initialWorkspaceFolders.stream().map(WorkspaceFolder::getUri)
					.map(URI::create).toArray(URI[]::new), null);
		} catch (Exception e) {
			error = e;
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
			stop(currentRegistry);
		} finally {
			if (progressMonitor != null) {
				String message = error != null ? "Maven initialization terminated with error " + error.getMessage()
						: "Maven initialization done";
				progressMonitor.end(message);
			}
		}
	}

	private static String getStepMessage(int currentStep, int nbSteps) {
		return " (" + currentStep + "/" + nbSteps + ")";
	}

	private MavenExecutionRequest initMavenRequest(PlexusContainer container, XMLMavenSettings options)
			throws Exception {
		MavenExecutionRequest mavenRequest = new DefaultMavenExecutionRequest();
		Properties systemProperties = getSystemProperties();
		mavenRequest.setSystemProperties(systemProperties);
		final File globalSettingsFile = getFileFromOptions(options.getGlobalSettings(),
				SettingsXmlConfigurationProcessor.DEFAULT_GLOBAL_SETTINGS_FILE);
		final File localSettingsFile = getFileFromOptions(options.getUserSettings(),
				SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE);

		Settings settings = buildSettings(container, globalSettingsFile.canRead() ? globalSettingsFile : null,
				localSettingsFile.canRead() ? localSettingsFile : null, systemProperties);

		if (settings != null) {
			if (globalSettingsFile.canRead()) {
				mavenRequest.setGlobalSettingsFile(globalSettingsFile);
			}
			if (localSettingsFile.canRead()) {
				mavenRequest.setUserSettingsFile(localSettingsFile);
			}
			MavenExecutionRequestPopulator requestPopulator = container
					.lookup(MavenExecutionRequestPopulator.class);
			requestPopulator.populateFromSettings(mavenRequest, settings);
		}

		LocalRepositoryUtils.updateLocalRepositoryPath(mavenRequest, options);

		RepositorySystem repositorySystem = container.lookup(RepositorySystem.class);
		ArtifactRepository localRepo = repositorySystem.createLocalRepository(mavenRequest.getLocalRepositoryPath());
		mavenRequest.setLocalRepository(localRepo);
		List<ArtifactRepository> defaultRemoteRepositories = Collections
				.singletonList(repositorySystem.createDefaultRemoteRepository());
		mavenRequest.setRemoteRepositories(
				joinRemoteRepositories(mavenRequest.getRemoteRepositories(), defaultRemoteRepositories));
		mavenRequest.setPluginArtifactRepositories(
				joinRemoteRepositories(mavenRequest.getPluginArtifactRepositories(), defaultRemoteRepositories));
		mavenRequest.setSystemProperties(systemProperties);
		mavenRequest.setCacheNotFound(true);
		mavenRequest.setCacheTransferError(true);
		mavenRequest.setWorkspaceReader(workspaceReader);
		return mavenRequest;
	}

	public Settings buildSettings(PlexusContainer container, File globalSettingsFile, File userSettingsFile,
			Properties systemProperties) {
		SettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
		request.setGlobalSettingsFile(globalSettingsFile);
		request.setUserSettingsFile(userSettingsFile != null ? userSettingsFile
				: SettingsXmlConfigurationProcessor.DEFAULT_USER_SETTINGS_FILE);
		request.setSystemProperties(systemProperties);
		try {
			return container.lookup(SettingsBuilder.class).build(request).getEffectiveSettings();
		} catch (SettingsBuildingException | ComponentLookupException ex) {
			return null;
		}
	}

	/*
	 * Thread-safe properties copy implementation. <p> {@link Properties#entrySet()}
	 * iterator is not thread safe and fails with {@link
	 * ConcurrentModificationException} if the source properties "is structurally
	 * modified at any time after the iterator is created". The solution is to use
	 * thread-safe {@link Properties#stringPropertyNames()} enumerate and copy
	 * properties.
	 *
	 * @see https://bugs.eclipse.org/bugs/show_bug.cgi?id=440696
	 */
	private static Properties getSystemProperties() {
		Properties systemProperties = new Properties();
		EnvironmentUtils.addEnvVars(systemProperties);
		Properties from = System.getProperties();
		for (String key : from.stringPropertyNames()) {
			String value = from.getProperty(key);
			if (value != null) {
				systemProperties.put(key, value);
			}
		}
		return systemProperties;
	}

	private List<ArtifactRepository> joinRemoteRepositories(List<ArtifactRepository> a, List<ArtifactRepository> b) {
		if (a.isEmpty()) {
			return b;
		}
		List<ArtifactRepository> remotes = new ArrayList<>(a);
		remotes.addAll(b);
		return remotes;
	}

	private static File getFileFromOptions(String element, File defaults) {
		if (element == null) {
			return defaults;
		}
		if (!element.trim().isEmpty()) {
			File globalSettingsCandidate = new File(element);
			if (globalSettingsCandidate.canRead()) {
				return globalSettingsCandidate;
			}
		}
		return defaults;
	}

	/* Copied from m2e */
	private DefaultPlexusContainer newPlexusContainer() throws PlexusContainerException {
		final ClassWorld classWorld = new ClassWorld(MAVEN_XMLLS_EXTENSION_REALM_ID, ClassWorld.class.getClassLoader());
		final ClassRealm realm;
		try {
			realm = classWorld.getRealm(MAVEN_XMLLS_EXTENSION_REALM_ID);
		} catch (NoSuchRealmException e) {
			try {
				classWorld.close();
			} catch (IOException ex) {
				LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
			}
			throw new PlexusContainerException("Could not lookup required class realm", e);
		}
		final ContainerConfiguration mavenCoreCC = new DefaultContainerConfiguration() //
				.setClassWorld(classWorld) //
				.setRealm(realm) //
				.setClassPathScanning(PlexusConstants.SCANNING_INDEX) //
				.setAutoWiring(true) //
				.setName("mavenCore"); //$NON-NLS-1$

		// final Module logginModule = new AbstractModule() {
		// protected void configure() {
		// bind(ILoggerFactory.class).toInstance(LoggerFactory.getILoggerFactory());
		// }
		// };
		// final Module coreExportsModule = new AbstractModule() {
		// protected void configure() {
		// ClassRealm realm = mavenCoreCC.getRealm();
		// CoreExtensionEntry entry = CoreExtensionEntry.discoverFrom(realm);
		// CoreExports exports = new CoreExports(entry);
		// bind(CoreExports.class).toInstance(exports);
		// }
		// };
		return new DefaultPlexusContainer(mavenCoreCC);
	}

	@Override
	public void stop(XMLExtensionsRegistry registry) {
		registry.unregisterRenameParticipant(propertyRenameParticipant);
		this.propertyRenameParticipant = null;
		unregisterCodeActionParticipants(registry);
		registry.unregisterCompletionParticipant(completionParticipant);
		this.completionParticipant = null;
		registry.unregisterDiagnosticsParticipant(diagnosticParticipant);
		this.diagnosticParticipant = null;
		registry.unregisterHoverParticipant(hoverParticipant);
		this.hoverParticipant = null;
		registry.unregisterDefinitionParticipant(definitionParticipant);
		this.definitionParticipant = null;
		if (localRepositorySearcher != null) {
			localRepositorySearcher.stop();
			localRepositorySearcher = null;
		}
		if (centralSearcher != null) {
			centralSearcher.stop();
			centralSearcher = null;
		}
		if (cache != null) {
			cache.stop();
			cache = null;
		}
		if (container != null) {
			container.dispose();
			container = null;
		}
		this.mavenSession = null;
		this.currentRegistry = null;
		if (mavenInitializer != null) {
			mavenInitializer.cancel(true);
		}
	}

	/**
	 * Checks if the specified document is to be treated as a Maven Project
	 * 
	 * @param document A document to be checked
	 * @return true If the specified document is to be treated as a Maven Project
	 */
	public static boolean match(DOMDocument document) {
		try {
			return match(new File(URI.create(document.getDocumentURI())).toPath());
		} catch (Exception ex) {
			// usually because of not so tolerant Java URI API
			LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
			return false;
		}
	}

	/**
	 * Checks if the specified file is to be treated as a Maven Project
	 * 
	 * @param file A file to be checked
	 * @return true If the specified file is to be treated as a Maven Project
	 */
	public static boolean match(Path file) {
		String fileName = file != null ? file.getFileName().toString() : null;
		return fileName != null && ((fileName.startsWith("pom") && fileName.endsWith(".xml"))
				|| fileName.endsWith(Maven.POMv4) || fileName.endsWith(".pom"));
	}

	/**
	 * Returns the instance of IXMLValidationService configured on the extension
	 * 
	 * @return IXMLValidationService object
	 */
	public IXMLValidationService getValidationService() {
		return validationService;
	}

	/**
	 * Returns the cache of collected Maven Projects
	 * 
	 * @return Maven Project Cache support
	 */
	public MavenProjectCache getProjectCache() {
		initialize();
		return this.cache;
	}

	/**
	 * Returns the Maven Session object
	 * 
	 * @return Maven Session object
	 */
	public MavenSession getMavenSession() {
		initialize();
		return this.mavenSession;
	}

	/**
	 * Returns the Plexus Container object
	 * 
	 * @return Plexus Container object
	 */
	public PlexusContainer getPlexusContainer() {
		initialize();
		return this.container;
	}

	/**
	 * Returns the Maven Build Plugin Manager object
	 * 
	 * @return Maven Build Plugin Manager object
	 */
	public BuildPluginManager getBuildPluginManager() {
		initialize();
		return buildPluginManager;
	}

	/**
	 * Returns the Local Maven Repository Searcher
	 * 
	 * @return Local Maven Repository Searcher
	 */
	public LocalRepositorySearcher getLocalRepositorySearcher() {
		initialize();
		return localRepositorySearcher;
	}

	/**
	 * Returns the Maven Plugin Manager object
	 * 
	 * @return Maven Plugin Manager object
	 */
	public MavenPluginManager getMavenPluginManager() {
		initialize();
		return mavenPluginManager;
	}

	/**
	 * Returns the Remote Maven Searcher (uses Maven Search API) instance
	 * 
	 * @return Optional RemoteCentralRepositorySearcher object
	 */
	public Optional<RemoteCentralRepositorySearcher> getCentralSearcher() {
		initialize();
		return Optional.ofNullable(centralSearcher);
	}

	/**
	 * Returns the URI Resolver Extension Manager
	 * 
	 * @return URI Resolver Extension Manager object
	 */
	public URIResolverExtensionManager getUriResolveExtentionManager() {
		initialize();
		return resolverExtensionManager;
	}

	/**
	 * Returns set of URI objects representing the folders available in Workspace
	 * 
	 * @return A linkedHashSet of Workspace folders URI
	 */
	public LinkedHashSet<URI> getCurrentWorkspaceFolders() {
		return currentWorkspaceFolders;
	}

	/**
	 * A handler to be called on changing the Workspace Folders
	 * 
	 * @param added   An array of added folders
	 * @param removed An array of removed folders
	 */
	public void didChangeWorkspaceFolders(URI[] added, URI[] removed) {
		CompletableFuture<Void> initializer = getMavenInitializer();
		if (initializer.isDone()) {
			internalDidChangeWorkspaceFolders(added, removed);
		} else {
			initializer.thenAccept(Void -> internalDidChangeWorkspaceFolders(added, removed));
		}
	}

	private void internalDidChangeWorkspaceFolders(URI[] added, URI[] removed) {
		currentWorkspaceFolders.addAll(List.of(added != null ? added : new URI[0]));
		currentWorkspaceFolders.removeAll(List.of(removed != null ? removed : new URI[0]));
		WorkspaceReader workspaceReader = mavenRequest.getWorkspaceReader();
		if (workspaceReader instanceof MavenLemminxWorkspaceReader reader) {
			Collection<URI> projectsToAdd = computeAddedWorkspaceProjects(added != null ? added : new URI[0]);
			Collection<URI> projectsToRemove = computeRemovedWorkspaceProjects(removed != null ? removed : new URI[0]);

			reader.addToWorkspace(sortProjects(projectsToAdd));
			projectsToRemove.stream().forEach(reader::remove);
		}
	}

	private Collection<URI> sortProjects(Collection<URI> projectsUris) {
		HashMap<URI, String> depByUri = new HashMap<>();
		HashMap<String, URI> uriByDep = new HashMap<>();
		LinkedHashMap<String, String> parentByDep = new LinkedHashMap<>();
		LinkedHashSet<URI> resultUris = new LinkedHashSet<>();

		Optional.ofNullable(projectsUris).ifPresent(uris -> {
			uris.stream().filter(Objects::nonNull).forEach(uri -> {
				Optional.ofNullable(createDOMDocument(uri)).ifPresent(doc -> {
					if (doc.getDocumentElement() != null
							&& PROJECT_ELT.equals(doc.getDocumentElement().getNodeName())) {
						Optional.ofNullable(MavenParseUtils.parseArtifact(doc.getDocumentElement())).ifPresent(a -> {
							Parent p = MavenParseUtils.parseParent(
									DOMUtils.findChildElement(doc.getDocumentElement(), PARENT_ELT).orElse(null));
							// If artifact groupId is null - set it from parent
							if (a.getGroupId() == null && p != null) {
								a.setGroupId(p.getGroupId());
							}
							// If artifact version is null - set it from parent
							if (a.getVersion() == null && p != null) {
								a.setVersion(p.getVersion());
							}

							String key = key(a);
							depByUri.put(uri, key);
							uriByDep.put(key, uri);
							if (p != null) {
								parentByDep.put(key, key(p));
							}
						});
					}
				});
			});

			LinkedHashSet<URI> skippedUris = new LinkedHashSet<>();
			uris.stream().filter(Objects::nonNull).forEach(uri -> {
				String a = depByUri.get(uri);
				if (a != null) {
					adUrisdParentFirst(a, parentByDep, uriByDep, resultUris);
				} else {
					skippedUris.add(uri);
				}
			});
			// Add skipped IRIs to the end of the collection
			resultUris.addAll(skippedUris);
		});
		return resultUris;
	}

	private static DOMDocument createDOMDocument(URI uri) {
		File pomFile = new File(uri);
		try {
			String fileContent = String.join(System.lineSeparator(), Files.readAllLines(pomFile.toPath()));
			return DOMParser.getInstance().parse(new TextDocument(fileContent, pomFile.toURI().toString()), null);
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		return null;
	}

	/**
	 * Creates a GAV key for a given Artifact
	 * 
	 * @param artifact
	 * @return GAV key string
	 */
	public static String key(Dependency artifact) {
		return Optional.ofNullable(artifact.getGroupId()).orElse("") + ':'
				+ Optional.ofNullable(artifact.getArtifactId()).orElse("") + ':'
				+ Optional.ofNullable(artifact.getVersion()).orElse("");
	}

	/**
	 * Creates a GAV key for a given Parent
	 * 
	 * @param artifact
	 * @return GAV key string
	 */
	public static String key(Parent parent) {
		return Optional.ofNullable(parent.getGroupId()).orElse("") + ':'
				+ Optional.ofNullable(parent.getArtifactId()).orElse("") + ':'
				+ Optional.ofNullable(parent.getVersion()).orElse("");
	}

	/**
	 * Creates a GAV key for a given Maven Project
	 * 
	 * @param artifact
	 * @return GAV key string
	 */
	public static String key(MavenProject project) {
		return Optional.ofNullable(project.getGroupId()).orElse("") + ':'
				+ Optional.ofNullable(project.getArtifactId()).orElse("") + ':'
				+ Optional.ofNullable(project.getVersion()).orElse("");
	}

	private void adUrisdParentFirst(String artifactKey, LinkedHashMap<String, String> parentByDep,
			HashMap<String, URI> uriByDep, LinkedHashSet<URI> resultUris) {

		String pKey = parentByDep.get(artifactKey);
		if (pKey != null) {
			adUrisdParentFirst(pKey, parentByDep, uriByDep, resultUris);
		}

		URI uri = uriByDep.get(artifactKey);
		if (uri != null) {
			resultUris.add(uri);
		}
	}

	private Collection<URI> computeRemovedWorkspaceProjects(URI[] removed) {
		return workspaceReader.getCurrentWorkspaceArtifactFiles().stream()
				.filter(f -> Arrays.stream(removed).anyMatch(uri -> {
					Path removedPath = new File(uri).toPath();
					return f.toPath().startsWith(removedPath);
				})).map(File::toURI).collect(Collectors.toUnmodifiableList());
	}

	private List<URI> computeAddedWorkspaceProjects(URI[] added) {
		List<URI> projectsToAdd = new ArrayList<>();
		Arrays.asList(added).stream().forEach(uri -> {
			Path addedPath = new File(uri).toPath();
			try {
				Files.walkFileTree(addedPath, Collections.emptySet(), 10, new SimpleFileVisitor<Path>() {
					@Override
					public FileVisitResult preVisitDirectory(Path file, BasicFileAttributes attrs) throws IOException {
						String fileName = file.getFileName().toString();
						// Skip hidden files and directories as well as 'target' directories
						if (fileName.charAt(0) == '.' || "target".equals(fileName)) {
							return FileVisitResult.SKIP_SUBTREE;
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (match(file)) {
							projectsToAdd.add(file.toUri());
						}
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}
		});

		return projectsToAdd;
	}

	/**
	 * Returns the list of Maven Projects currently added to the Workspace
	 * 
	 * @param wait A boolean 'true' indicates that all projects are to be returned,
	 *             not only the cached ones at the moment, method should wait for
	 *             the final build result, otherwise the only project that are
	 *             already built and cached are to be returned, the rest of the
	 *             projects are to be built in background
	 * @return List of Maven Projects
	 */
	public List<MavenProject> getCurrentWorkspaceProjects(boolean wait) {
		return workspaceReader.getCurrentWorkspaceArtifactFiles().stream().map(file -> {
			try {
				CompletableFuture<LoadedMavenProject> loadedProject = getProjectCache()
						.getLoadedMavenProject(toUriASCIIString(file));
				return wait ? loadedProject.get(WAIT_SAFE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
						: loadedProject.getNow(null);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
				return null;
			}
		}).filter(Objects::nonNull).map(LoadedMavenProject::getMavenProject).toList();
	}

	/**
	 * Returns the list of Maven Project files currently added to the Workspace
	 * 
	 * @return List of Maven Project files
	 */
	public List<File> getCurrentWorkspaceProjectFiles() {
		return workspaceReader.getCurrentWorkspaceArtifactFiles().stream().filter(Objects::nonNull).toList();
	}

	private void registerCodeActionParticipants(XMLExtensionsRegistry registry) {
		if (codeActionParticipants.isEmpty()) {
			synchronized (codeActionParticipants) {
				if (!codeActionParticipants.isEmpty()) {
					return;
				}
				codeActionParticipants.add(new MavenNoGrammarConstraintsCodeAction());
				codeActionParticipants.add(new MavenIdPartRemovalCodeAction());
				codeActionParticipants.add(new MavenManagedVersionRemovalCodeAction());

				// Refactoring
				codeActionParticipants.add(new InlinePropertyCodeAction(this));
				codeActionParticipants.add(new ExtractPropertyCodeAction(this));

				codeActionParticipants.stream().forEach(registry::registerCodeActionParticipant);
			}
		}
	}

	private void unregisterCodeActionParticipants(XMLExtensionsRegistry registry) {
		synchronized (codeActionParticipants) {
			codeActionParticipants.stream().forEach(registry::unregisterCodeActionParticipant);
			codeActionParticipants.clear();
		}
	}

	/**
	 * Returns true if the lemminx maven is run in JUnit test context and false
	 * otherwise.
	 * 
	 * @return true if the lemminx maven is run in JUnit test context and false
	 *         otherwise
	 */
	public static boolean isUnitTestMode() {
		return MavenLemminxExtension.unitTestMode;
	}

	/**
	 * Set true if the lemminx maven is run in JUnit test context and false
	 * otherwise
	 * 
	 * @param unitTestMode true if the lemminx maven is run in JUnit test context
	 *                     and false otherwise
	 */
	public static void setUnitTestMode(boolean unitTestMode) {
		MavenLemminxExtension.unitTestMode = unitTestMode;
	}

	/**
	 * Gets a normalized URI ASCII string from the given File
	 * 
	 * @param file A File
	 * @return Normalized URI ASCII string
	 */
	public static String toUriASCIIString(File file) {
		return file.toURI().normalize().toASCIIString();
	}

	@Override
	public void builtMavenProject(final File repository, final MavenProject mavenProject) {
		mavenProjectBuildListeners.stream().forEach(l -> {
			try {	
				l.builtMavenProject(repository, mavenProject);
			}catch (Exception e) {
				// Ignore
			}
		});
	}
}
