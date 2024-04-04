package net.coderbot.iris.gl.program;


import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.GlResource;
import net.coderbot.iris.gl.IrisRenderSystem;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.vendored.joml.Vector2f;
import net.coderbot.iris.vendored.joml.Vector3i;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL43;

public final class ComputeProgram extends GlResource {
    private final ProgramUniforms uniforms;
    private final ProgramSamplers samplers;
    private final ProgramImages images;
    private final int[] localSize;
    private Vector3i absoluteWorkGroups;
    private Vector2f relativeWorkGroups;
    private float cachedWidth;
    private float cachedHeight;
    private Vector3i cachedWorkGroups;

    ComputeProgram(int program, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images) {
        super(program);

        localSize = new int[3];
        IrisRenderSystem.getProgramiv(program, GL43.GL_COMPUTE_WORK_GROUP_SIZE, localSize);
        this.uniforms = uniforms;
        this.samplers = samplers;
        this.images = images;
    }

    public static void unbind() {
        ProgramUniforms.clearActiveUniforms();
        OpenGlHelper.glUseProgram(0);
    }

    public void setWorkGroupInfo(Vector2f relativeWorkGroups, Vector3i absoluteWorkGroups) {
        this.relativeWorkGroups = relativeWorkGroups;
        this.absoluteWorkGroups = absoluteWorkGroups;
    }

    public Vector3i getWorkGroups(float width, float height) {
        if (cachedWidth != width || cachedHeight != height || cachedWorkGroups == null) {
            this.cachedWidth = width;
            this.cachedHeight = height;
            if (this.absoluteWorkGroups != null) {
                this.cachedWorkGroups = this.absoluteWorkGroups;
            } else if (relativeWorkGroups != null) {
                // TODO: This is my best guess at what Optifine does. Can this be confirmed?
                // Do not use actual localSize here, apparently that's not what we want.
                this.cachedWorkGroups = new Vector3i((int) Math.ceil(Math.ceil((width * relativeWorkGroups.x)) / localSize[0]), (int) Math.ceil(Math.ceil((height * relativeWorkGroups.y)) / localSize[1]), 1);
            } else {
                this.cachedWorkGroups = new Vector3i((int) Math.ceil(width / localSize[0]), (int) Math.ceil(height / localSize[1]), 1);
            }
        }

        return cachedWorkGroups;
    }

    public void use() {
        OpenGlHelper.glUseProgram(getGlId());

        uniforms.update();
        samplers.update();
        images.update();
    }

    public void dispatch(float width, float height) {
        OpenGlHelper.glUseProgram(getGlId());
        uniforms.update();
        samplers.update();
        images.update();

        if (!Iris.getPipelineManager().getPipeline().map(WorldRenderingPipeline::allowConcurrentCompute).orElse(false)) {
            IrisRenderSystem.memoryBarrier(40);
        }

        IrisRenderSystem.dispatchCompute(getWorkGroups(width, height));
    }

    public void destroyInternal() {
        OpenGlHelper.glDeleteProgram(getGlId());
    }

    /**
     * @return the OpenGL ID of this program.
     * @deprecated this should be encapsulated eventually
     */
    @Deprecated
    public int getProgramId() {
        return getGlId();
    }

    public int getActiveImages() {
        return images.getActiveImages();
    }
}
