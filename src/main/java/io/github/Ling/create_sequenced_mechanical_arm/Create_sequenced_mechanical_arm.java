package io.github.Ling.create_sequenced_mechanical_arm;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import io.github.Ling.create_sequenced_mechanical_arm.mixin.ArmInteractionPointAccessor;
import io.github.Ling.create_sequenced_mechanical_arm.sequencedarm.SequencedArmRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Create_sequenced_mechanical_arm.MODID)
public class Create_sequenced_mechanical_arm {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "create_sequenced_mechanical_arm";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();

    // 本模组自己的创造模式分页；物品清单在 SequencedArmRegistration 里
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> SEQUENCED_ARM_TAB =
        CREATIVE_MODE_TABS.register("sequenced_arm", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + MODID))
            .icon(() -> SequencedArmRegistration.SEQUENCED_ARM_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(SequencedArmRegistration.SEQUENCED_ARM_ITEM.get());
                output.accept(SequencedArmRegistration.OPERATION_TABLE_RELATIVE.get());
                // 操作记录表（上传用：手持 + 潜行右键一条臂 ⇒ 导出脚本文件）
                output.accept(SequencedArmRegistration.OPERATION_RECORD.get());
            })
            .build());

    public Create_sequenced_mechanical_arm(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        CREATIVE_MODE_TABS.register(modEventBus);

        // 可编程动力臂：方块 / 物品 / 方块实体（见 PLAN.md 阶段 1）
        SequencedArmRegistration.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        NeoForge.EVENT_BUS.register(this);

        // 以前这里注册的是 MDK 示例配置（`Config.SPEC`：logDirtBlock / magicNumber / 一串要打日志的物品）——
        // 那四个配置项全工程没有任何消费点，却会实打实生成一份 config 文件。删掉它，
        // 等真出现"需要给玩家调的参数"时再建一个名副其实的 Config（PLAN §11 决策 69）。
        // 现在它来了：规模上限（任务数 / 信号数 / 条目总数）做成 COMMON 配置 ——
        // 两端各自读自己那份 config/create_sequenced_mechanical_arm-common.toml（单人游戏里是同一个文件）。
        modContainer.registerConfig(ModConfig.Type.COMMON, SequencedArmConfig.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Sequenced Mechanical Arm: common setup");
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
        logAccessorState();
    }

    /**
     * 硬校验：Mixin 访问器是否真的注入到了 Create 的交互点类上。
     * 若没注入，取放物品时的 {@code (ArmInteractionPointAccessor)} 强转会抛 ClassCastException；
     * 这里提前把它变成一行日志，省掉一次"游戏里才炸"的排查。
     */
    private static void logAccessorState() {
        LOGGER.info("ArmInteractionPoint accessor applied: {}",
            ArmInteractionPointAccessor.class.isAssignableFrom(ArmInteractionPoint.class));
    }

    // You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
            // 客户端读的是**自己那份** config 文件（专用服务器上可与服务端不同 ⇒ 界面提示只是提前告知，
            // 真正的裁决在服务端）。这行日志就是"客户端这一侧的生效值"的现场证据。
            LOGGER.info("Sequenced Mechanical Arm 客户端 {}", SequencedArmConfig.effectiveSummary());
            // 客户端是渲染所在侧，这条决定了取物位置是否精确（臂顶端会不会插进方块里）
            logAccessorState();
        }
    }
}
