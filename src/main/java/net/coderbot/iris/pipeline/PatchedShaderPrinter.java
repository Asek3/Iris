package net.coderbot.iris.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import net.coderbot.iris.Iris;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Static class that deals with printing the patched_shader folder.
 */
public class PatchedShaderPrinter {
	private static boolean outputLocationCleared = false;
	private static int programCounter = 0;
	public static final boolean prettyPrintShaders = !FMLLoader.isProduction()
			|| System.getProperty("iris.prettyPrintShaders", "false").equals("true");

	public static void resetPrintState() {
		outputLocationCleared = false;
		programCounter = 0;
	}

	public static void debugPatchedShaders(String name, String vertex, String geometry, String fragment) {
		if (prettyPrintShaders) {
			final Path debugOutDir = FMLPaths.GAMEDIR.get().resolve("patched_shaders");
			if (!outputLocationCleared) {
				try {
					if (Files.exists(debugOutDir)) {
						try (Stream<Path> stream = Files.list(debugOutDir)) {
							stream.forEach(path -> {
								try {
									Files.delete(path);
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							});
						}
					}

					Files.createDirectories(debugOutDir);
				} catch (IOException e) {
					Iris.logger.warn("Failed to initialize debug patched shader source location", e);
				}
				outputLocationCleared = true;
			}

			try {
				programCounter++;
				String prefix = String.format("%03d_", programCounter);
				if (vertex != null) {
					Files.write(debugOutDir.resolve(prefix + name + ".vsh"), vertex.getBytes(StandardCharsets.UTF_8));
				}
				if (geometry != null) {
					Files.write(debugOutDir.resolve(prefix + name + ".gsh"), geometry.getBytes(StandardCharsets.UTF_8));
				}
				if (fragment != null) {
					Files.write(debugOutDir.resolve(prefix + name + ".fsh"), fragment.getBytes(StandardCharsets.UTF_8));
				}
			} catch (IOException e) {
				Iris.logger.warn("Failed to write debug patched shader source", e);
			}
		}
	}
}
