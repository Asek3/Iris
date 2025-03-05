package net.irisshaders.iris.shaderpack;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.TextureDefinition;
import net.irisshaders.iris.gui.FeatureMissingErrorScreen;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.include.ShaderPackSourceNames;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.option.OrderBackedProperties;
import net.irisshaders.iris.shaderpack.option.ProfileSet;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuContainer;
import net.irisshaders.iris.shaderpack.option.values.MutableOptionValues;
import net.irisshaders.iris.shaderpack.option.values.OptionValues;
import net.irisshaders.iris.shaderpack.parsing.BooleanParser;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSetInterface;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ShaderPack {
	private static final Logger LOGGER = LoggerFactory.getLogger(ShaderPack.class);
	private static final Gson GSON = new Gson();
	private static final ExecutorService ASYNC_TEXTURE_EXECUTOR = Executors.newWorkStealingPool(
			Runtime.getRuntime().availableProcessors()
	);
	private static final Map<String, Integer> SHADER_BINARY_CACHE = new ConcurrentHashMap<>();

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			ASYNC_TEXTURE_EXECUTOR.shutdown();
			try {
				if (!ASYNC_TEXTURE_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
					ASYNC_TEXTURE_EXECUTOR.shutdownNow();
				}
			} catch (InterruptedException e) {
				ASYNC_TEXTURE_EXECUTOR.shutdownNow();
			}
		}));
	}

	private final CustomUniforms.Builder customUniforms;
	private final TextureLoader textureLoader = new TextureLoader();
	private final ProgramSet base;
	private final Map<NamespacedId, ProgramSetInterface> overrides;
	private final IdMap idMap;
	private final LanguageMap languageMap;
	private final EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> customTextureDataMap = new EnumMap<>(TextureStage.class);
	private final Object2ObjectMap<String, CustomTextureData> irisCustomTextureDataMap = new Object2ObjectOpenHashMap<>();
	private final CustomTextureData customNoiseTexture;
	private final ShaderPackOptions shaderPackOptions;
	private final OptionMenuContainer menuContainer;
	private final ProfileSet.ProfileResult profile;
	private final String profileInfo;
	private final List<ImageInformation> irisCustomImages;
	private final Set<FeatureFlags> activeFeatures;
	private final Function<AbsolutePackPath, String> sourceProvider;
	private final ShaderProperties shaderProperties;
	private final List<String> dimensionIds;
	private Map<NamespacedId, String> dimensionMap;

	public ShaderPack(Path root, ImmutableList<StringPair> environmentDefines) throws IOException, IllegalStateException {
		this(root, Collections.emptyMap(), environmentDefines);
	}

	public ShaderPack(Path root, Map<String, String> changedConfigs, ImmutableList<StringPair> environmentDefines) throws IOException, IllegalStateException {
		Objects.requireNonNull(root);
		ArrayList<StringPair> envDefines1 = new ArrayList<>(environmentDefines);
		envDefines1.addAll(IrisDefines.createIrisReplacements());
		environmentDefines = ImmutableList.copyOf(envDefines1);
		ImmutableList.Builder<AbsolutePackPath> starts = ImmutableList.builder();
		ImmutableList<String> potentialFileNames = ShaderPackSourceNames.POTENTIAL_STARTS;
		ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/"), potentialFileNames);
		dimensionIds = new ArrayList<>();
		final boolean[] hasDimensionIds = {false};

		// Dimension properties loading
		List<String> dimensionIdCreator = loadProperties(root, "dimension.properties", environmentDefines).map(dimensionProperties -> {
			hasDimensionIds[0] = !dimensionProperties.isEmpty();
			dimensionMap = parseDimensionMap(dimensionProperties, "dimension.", "dimension.properties");
			return parseDimensionIds(dimensionProperties, "dimension.");
		}).orElse(new ArrayList<>());

		if (!hasDimensionIds[0]) {
			dimensionMap = new Object2ObjectArrayMap<>();
			if (Files.exists(root.resolve("world0"))) {
				dimensionIdCreator.add("world0");
				dimensionMap.putIfAbsent(DimensionId.OVERWORLD, "world0");
				dimensionMap.putIfAbsent(new NamespacedId("*", "*"), "world0");
			}
			if (Files.exists(root.resolve("world-1"))) {
				dimensionIdCreator.add("world-1");
				dimensionMap.putIfAbsent(DimensionId.NETHER, "world-1");
			}
			if (Files.exists(root.resolve("world1"))) {
				dimensionIdCreator.add("world1");
				dimensionMap.putIfAbsent(DimensionId.END, "world1");
			}
		}

		for (String id : dimensionIdCreator) {
			if (ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/" + id), potentialFileNames)) {
				dimensionIds.add(id);
			}
		}

		IncludeGraph graph = new IncludeGraph(root, starts.build());
		if (!graph.getFailures().isEmpty()) {
			graph.getFailures().forEach((path, error) -> LOGGER.error("Include resolution failed: {}", error));
			throw new IOException("Shader pack includes resolution failed");
		}

		this.languageMap = new LanguageMap(root.resolve("lang"));
		this.shaderPackOptions = new ShaderPackOptions(graph, changedConfigs);
		graph = this.shaderPackOptions.getIncludes();

		List<StringPair> finalEnvironmentDefines = new ArrayList<>(List.copyOf(environmentDefines));
		for (FeatureFlags flag : FeatureFlags.values()) {
			if (flag.isUsable()) finalEnvironmentDefines.add(new StringPair("IRIS_FEATURE_" + flag.name(), ""));
		}

		this.shaderProperties = loadPropertiesAsString(root, "shaders.properties", environmentDefines)
				.map(source -> new ShaderProperties(source, shaderPackOptions, finalEnvironmentDefines))
				.orElseGet(ShaderProperties::empty);

		activeFeatures = new HashSet<>();
		for (int i = 0; i < shaderProperties.getRequiredFeatureFlags().size(); i++) {
			activeFeatures.add(FeatureFlags.getValue(shaderProperties.getRequiredFeatureFlags().get(i)));
		}
		for (int i = 0; i < shaderProperties.getOptionalFeatureFlags().size(); i++) {
			activeFeatures.add(FeatureFlags.getValue(shaderProperties.getOptionalFeatureFlags().get(i)));
		}

		if (!activeFeatures.contains(FeatureFlags.SSBO) && !shaderProperties.getBufferObjects().isEmpty()) {
			throw new IllegalStateException("SSBO feature required but not enabled");
		}

		if (!activeFeatures.contains(FeatureFlags.CUSTOM_IMAGES) && !shaderProperties.getIrisCustomImages().isEmpty()) {
			throw new IllegalStateException("CUSTOM_IMAGES feature required but not enabled");
		}

		List<FeatureFlags> invalidFlagList = shaderProperties.getRequiredFeatureFlags().stream()
				.filter(FeatureFlags::isInvalid)
				.map(FeatureFlags::getValue)
				.collect(Collectors.toList());
		List<String> invalidFeatureFlags = invalidFlagList.stream()
				.map(FeatureFlags::getHumanReadableName)
				.toList();

		if (!invalidFeatureFlags.isEmpty() && Minecraft.getInstance().screen instanceof ShaderPackScreen) {
			MutableComponent component = Component.translatable("iris.unsupported.pack.description",
					FeatureFlags.getInvalidStatus(invalidFlagList),
					invalidFeatureFlags.stream().collect(Collectors.joining(", ", ": ", "."))
			);
			if (SystemUtils.IS_OS_MAC) {
				component = component.append(Component.translatable("iris.unsupported.pack.macos"));
			}
			Minecraft.getInstance().setScreen(new FeatureMissingErrorScreen(
					Minecraft.getInstance().screen,
					Component.translatable("iris.unsupported.pack"),
					component
			));
			IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
		}

		List<StringPair> newEnvDefines = new ArrayList<>(environmentDefines);
		if (shaderProperties.supportsColorCorrection().orElse(false)) {
			for (ColorSpace space : ColorSpace.values()) {
				newEnvDefines.add(new StringPair("COLOR_SPACE_" + space.name(), String.valueOf(space.ordinal())));
			}
		}

		List<String> optionalFeatureFlags = shaderProperties.getOptionalFeatureFlags().stream()
				.filter(flag -> !FeatureFlags.isInvalid(flag))
				.toList();
		if (!optionalFeatureFlags.isEmpty()) {
			optionalFeatureFlags.forEach(flag -> newEnvDefines.add(new StringPair("IRIS_FEATURE_" + flag, "")));
		}

		environmentDefines = ImmutableList.copyOf(newEnvDefines);
		ProfileSet profiles = ProfileSet.fromTree(shaderProperties.getProfiles(), this.shaderPackOptions.getOptionSet());
		this.profile = profiles.scan(this.shaderPackOptions.getOptionSet(), this.shaderPackOptions.getOptionValues());

		List<String> disabledPrograms = new ArrayList<>();
		this.profile.current.ifPresent(profile -> disabledPrograms.addAll(profile.disabledPrograms));
		shaderProperties.getConditionallyEnabledPrograms().forEach((program, shaderOption) -> {
			if (!BooleanParser.parse(shaderOption, this.shaderPackOptions.getOptionValues())) {
				disabledPrograms.add(program);
			}
		});

		this.menuContainer = new OptionMenuContainer(shaderProperties, this.shaderPackOptions, profiles);

		String profileName = getCurrentProfileName();
		OptionValues profileOptions = new MutableOptionValues(
				this.shaderPackOptions.getOptionSet(),
				this.profile.current.map(p -> p.optionValues).orElse(new HashMap<>())
		);
		int userOptionsChanged = this.shaderPackOptions.getOptionValues().getOptionsChanged() - profileOptions.getOptionsChanged();
		this.profileInfo = String.format("Profile: %s (+%d %s changed)",
				profileName, userOptionsChanged, (userOptionsChanged == 1 ? "option" : "options"));
		LOGGER.info("[Iris] {}", this.profileInfo);

		IncludeProcessor includeProcessor = new IncludeProcessor(graph);
		Iterable<StringPair> finalEnvironmentDefines1 = environmentDefines;
		this.sourceProvider = path -> {
			String pathString = path.getPathString();
			int startIndex = pathString.startsWith("/") ? 1 : 0;
			String programString = pathString.substring(startIndex, pathString.lastIndexOf('.'));
			if (disabledPrograms.contains(programString)) return null;

			ImmutableList<String> lines = includeProcessor.getIncludedFile(path);
			if (lines == null) return null;

			return JcppProcessor.glslPreprocessSource(String.join("\n", lines), finalEnvironmentDefines1);
		};

		String defaultDimensionPath = dimensionMap.getOrDefault(new NamespacedId("*", "*"), "");
		this.base = new ProgramSet(
				AbsolutePackPath.fromAbsolutePath("/" + defaultDimensionPath),
				sourceProvider,
				shaderProperties,
				this
		);

		this.overrides = new HashMap<>();
		this.idMap = new IdMap(root, shaderPackOptions, environmentDefines);

		customNoiseTexture = shaderProperties.getNoiseTexturePath().map(path -> {
			try {
				return textureLoader.readTexture(root, new TextureDefinition.PNGDefinition(path));
			} catch (IOException e) {
				LOGGER.error("Failed to load noise texture: {}", path, e);
				return null;
			}
		}).orElse(null);

		shaderProperties.getCustomTextures().forEach((textureStage, customTexturePropertiesMap) -> {
			Object2ObjectMap<String, CustomTextureData> innerMap = new Object2ObjectOpenHashMap<>();
			customTexturePropertiesMap.forEach((samplerName, definition) -> {
				try {
					innerMap.put(samplerName, textureLoader.readTexture(root, definition));
				} catch (IOException e) {
					LOGGER.error("Failed to load custom texture {}: {}", samplerName, definition.getName(), e);
				}
			});
			customTextureDataMap.put(textureStage, innerMap);
		});

		this.irisCustomImages = shaderProperties.getIrisCustomImages();
		this.customUniforms = shaderProperties.getCustomUniforms();

		shaderProperties.getIrisCustomTextures().forEach((name, texture) -> {
			try {
				irisCustomTextureDataMap.put(name, textureLoader.readTexture(root, texture));
			} catch (IOException e) {
				LOGGER.error("Failed to load Iris custom texture {}: {}", name, texture.getName(), e);
			}
		});
	}

	private static Optional<String> loadPropertiesAsString(Path shaderPath, String name, Iterable<StringPair> environmentDefines) {
		try {
			String fileContents = Files.readString(shaderPath.resolve(name), StandardCharsets.ISO_8859_1);
			ImmutableList<StringPair> defines = ImmutableList.copyOf(environmentDefines);
			return Optional.of(PREPROCESS_CACHE.getUnchecked(new PreprocessKey(fileContents, defines)));
		} catch (NoSuchFileException e) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("An {} file was not found in the current shaderpack", name);
			}
			return Optional.empty();
		} catch (IOException e) {
			LOGGER.error("IO error reading properties: {} / {}", shaderPath, name, e);
			return Optional.empty();
		}
	}

	private static Optional<Properties> loadProperties(Path shaderPath, String name, Iterable<StringPair> environmentDefines) {
		return loadPropertiesAsString(shaderPath, name, environmentDefines).map(processed -> {
			Properties properties = new OrderBackedProperties();
			try {
				properties.load(new StringReader(processed));
			} catch (IOException e) {
				LOGGER.error("Properties parse error", e);
			}
			return properties;
		});
	}

	private static Map<NamespacedId, String> parseDimensionMap(Properties properties, String keyPrefix, String fileName) {
		return properties.entrySet().stream()
				.filter(entry -> ((String) entry.getKey()).startsWith(keyPrefix))
				.flatMap(entry -> {
					String key = ((String) entry.getKey()).substring(keyPrefix.length());
					String value = (String) entry.getValue();
					return Arrays.stream(value.split("\\s+"))
							.map(part -> part.equals("*")
									? new AbstractMap.SimpleEntry<>(new NamespacedId("*", "*"), key)
									: new AbstractMap.SimpleEntry<>(new NamespacedId(part), key));
				})
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private List<String> parseDimensionIds(Properties dimensionProperties, String keyPrefix) {
		return dimensionProperties.keySet().stream()
				.map(keyObj -> (String) keyObj)
				.filter(key -> key.startsWith(keyPrefix))
				.map(key -> key.substring(keyPrefix.length()))
				.collect(Collectors.toList());
	}

	private String getCurrentProfileName() {
		return profile.current.map(p -> p.name).orElse("Custom");
	}

	public String getProfileInfo() {
		return profileInfo;
	}

	public ProgramSet getProgramSet(NamespacedId dimension) {
		ProgramSetInterface override = overrides.computeIfAbsent(dimension, dim -> {
			if (dimensionMap.containsKey(dim)) {
				String name = dimensionMap.get(dim);
				if (dimensionIds.contains(name)) {
					return new ProgramSet(AbsolutePackPath.fromAbsolutePath("/" + name), sourceProvider, shaderProperties, this);
				} else {
					LOGGER.error("Missing dimension folder: {} for {}", name, dim);
					return ProgramSetInterface.Empty.INSTANCE;
				}
			}
			return ProgramSetInterface.Empty.INSTANCE;
		});
		return (override instanceof ProgramSet) ? (ProgramSet) override : base;
	}


	// Helper classes and remaining methods
	private static final class PreprocessKey {
		private final String content;
		private final ImmutableList<StringPair> defines;

		PreprocessKey(String content, ImmutableList<StringPair> defines) {
			this.content = content.intern();
			this.defines = defines;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof PreprocessKey)) return false;
			PreprocessKey that = (PreprocessKey) o;
			return content.equals(that.content) && defines.equals(that.defines);
		}

		@Override
		public int hashCode() {
			return Objects.hash(content, defines);
		}
	}

	private static final LoadingCache<PreprocessKey, String> PREPROCESS_CACHE = CacheBuilder.newBuilder()
			.maximumSize(1000)
			.build(new CacheLoader<PreprocessKey, String>() {
				public String load(PreprocessKey key) {
					return PropertiesPreprocessor.preprocessSource(key.content, key.defines);
				}
			});

	// TextureLoader inner class
	private static class TextureLoader {
		private final Map<TextureDefinition, CompletableFuture<CustomTextureData>> textureCache = new ConcurrentHashMap<>();

		public CustomTextureData readTexture(Path root, TextureDefinition definition) throws IOException {
			try {
				return textureCache.computeIfAbsent(definition,
						def -> loadTextureAsync(root, def)
				).get(500, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				return createPlaceholderTexture();
			} catch (Exception e) {
				throw new IOException("Texture loading failed: " + definition.getName(), e);
			}
		}

		private CompletableFuture<CustomTextureData> loadTextureAsync(Path root, TextureDefinition definition) {
			return CompletableFuture.supplyAsync(() -> {
				try {
					Path texturePath = root.resolve(definition.getName());
					if (!Files.exists(texturePath)) {
						LOGGER.warn("Missing texture: {}", definition.getName());
						return createPlaceholderTexture();
					}

					byte[] data = Files.readAllBytes(texturePath);
					return new CustomTextureData.PngData(
							new TextureFilteringData(false, false),
							data
					);
				} catch (Exception e) {
					LOGGER.error("Failed to load texture", e);
					return createPlaceholderTexture();
				}
			}, ASYNC_TEXTURE_EXECUTOR);
		}

		private CustomTextureData createPlaceholderTexture() {
			return new CustomTextureData.PngData(
					new TextureFilteringData(false, false),
					new byte[0]
			);
		}
	}

	public CustomUniforms.Builder getCustomUniforms() {
		return this.customUniforms;
	}

	public IdMap getIdMap() {
		return idMap;
	}

	public EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> getCustomTextureDataMap() {
		return customTextureDataMap;
	}

	public List<ImageInformation> getIrisCustomImages() {
		return irisCustomImages;
	}

	public Object2ObjectMap<String, CustomTextureData> getIrisCustomTextureDataMap() {
		return irisCustomTextureDataMap;
	}

	public Optional<CustomTextureData> getCustomNoiseTexture() {
		return Optional.ofNullable(customNoiseTexture);
	}

	public LanguageMap getLanguageMap() {
		return languageMap;
	}

	public ShaderPackOptions getShaderPackOptions() {
		return shaderPackOptions;
	}

	public OptionMenuContainer getMenuContainer() {
		return menuContainer;
	}

	public boolean hasFeature(FeatureFlags feature) {
		return activeFeatures.contains(feature);
	}
}