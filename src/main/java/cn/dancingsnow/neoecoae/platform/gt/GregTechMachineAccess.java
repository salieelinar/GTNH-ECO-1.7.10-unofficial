package cn.dancingsnow.neoecoae.platform.gt;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import cn.dancingsnow.neoecoae.compat.gregtech.ECOGregTechMachineStatus;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** Native API linkage is isolated from the no-GT load path. */
final class GregTechMachineAccess {

    /** Native processing logic, read reflectively so a GT build without it only disables the precise parallel. */
    private static final Field PROCESSING_LOGIC = nativeField("processingLogic");
    /** GT's own run clock, used only to keep a just-finished cycle's parallel readable for a moment. */
    private static final Field RUN_TIME = nativeField("mTotalRunTime");
    private static final Field LAST_WORK = nativeField("mLastWorkingTick");
    private static final Method CURRENT_PARALLELS = nativeMethod(
        "gregtech.api.logic.ProcessingLogic",
        "getCurrentParallels");
    /** GTNL's pattern-input mirror hatches; absent on GT builds without the crafting input buffer. */
    private static final Method PROXY_HATCHES = nativeMethod(
        "gregtech.common.tileentities.machines.MTEHatchCraftingInputME",
        "getProxyHatches");
    /** Ticks a finished cycle's parallel stays readable, so fast machines do not blink to unknown. */
    private static final long IDLE_PARALLEL_GRACE_TICKS = 100L;
    /** Watcher list shared by GT's crafting input buffer and GTNL's super hatch; mirror proxies register there. */
    private static final Field WATCHERS = hatchField();
    /** These two controllers compute their accepted parallel outside ProcessingLogic. */
    private static final String ORE_FACTORY = "gregtech.common.tileentities.machines.multi.MTEIntegratedOreFactory";
    private static final String ORE_FACTORY_LEGACY = "gregtech.common.tileentities.machines.multi.MTEIntegratedOreFactoryLegacy";
    private static final ClassValue<Boolean> ORE_FACTORY_TYPES = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                if (ORE_FACTORY.equals(c.getName()) || ORE_FACTORY_LEGACY.equals(c.getName())) return true;
            return false;
        }
    };
    /** Parallel captured at the recipe-check boundary of the running cycle; never crosses a cycle. */
    private static final Map<MTEMultiBlockBase, CycleParallel> CYCLE_PARALLELS = new WeakHashMap<>();

    private static Field nativeField(String name) {
        try {
            Field field = MTEMultiBlockBase.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static Method nativeMethod(String className, String name) {
        try {
            return Class.forName(className)
                .getMethod(name);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private GregTechMachineAccess() {}

    static void verifyApi() {
        try {
            IGregTechTileEntity.class.getMethod("getMetaTileEntity");
            MTEBasicMachine.class.getField("mProgresstime");
            MTEBasicMachine.class.getField("mMaxProgresstime");
            MTEBasicMachine.class.getField("mEUt");
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("GregTech native machine API is incompatible", failure);
        }
    }

    /** A recipe controller's current cycle, or null for hatches and other non-processing tiles. */
    static ECOGregTechMachineStatus.Sample sample(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) return null;
        IGregTechTileEntity host = (IGregTechTileEntity) tile;
        IMetaTileEntity machine = host.getMetaTileEntity();
        String name = machine == null ? null : machine.getLocalName();
        if (name == null && machine != null) name = machine.getMetaName();
        if (machine instanceof MTEMultiBlockBase) {
            MTEMultiBlockBase multi = (MTEMultiBlockBase) machine;
            List<Object> outputs = new ArrayList<>();
            if (multi.mOutputItems != null) outputs.addAll(Arrays.asList(multi.mOutputItems));
            if (multi.mOutputFluids != null) outputs.addAll(Arrays.asList(multi.mOutputFluids));
            var result = multi.getCheckRecipeResult();
            return new ECOGregTechMachineStatus.Sample(
                name,
                outputs,
                host.getProgress(),
                host.getMaxProgress(),
                host.isActive(),
                host.isAllowedToWork(),
                false,
                multi.mMachine,
                result == null || result.wasSuccessful() ? null : result.getDisplayString(),
                currentParallels(multi),
                shutdown(host),
                maintenanceIssues(multi),
                false);
        }
        if (machine instanceof MTEBasicMachine) {
            MTEBasicMachine basic = (MTEBasicMachine) machine;
            List<Object> outputs = new ArrayList<>(Arrays.asList(basic.mOutputItems));
            if (basic.mOutputFluid != null) outputs.add(basic.mOutputFluid);
            return new ECOGregTechMachineStatus.Sample(
                name,
                outputs,
                host.getProgress(),
                host.getMaxProgress(),
                host.isActive(),
                host.isAllowedToWork(),
                basic.mStuttering,
                true,
                null,
                1,
                shutdown(host),
                0,
                basic.mOutputBlocked > 0);
        }
        return null;
    }

    /**
     * Current parallel of the accepted recipe cycle. GT clears {@code ProcessingLogic.calculatedParallels} on every
     * recipe check, so a status sample can land in the gap and read zero while the cycle still runs; the value is
     * also not persisted across reloads. Keep the value captured at the cycle boundary instead of reusing a duration
     * key or fabricating 1 — batch-driven machines such as the GTNL assembly line are the ones that depend on it.
     */
    static synchronized long currentParallels(MTEMultiBlockBase multi) {
        CycleParallel cycle = CYCLE_PARALLELS.get(multi);
        if (multi.mMaxProgresstime > 0) {
            long live = processingParallels(multi);
            if (live > 0) {
                rememberParallels(multi, live);
                return live;
            }
            if (cycle != null && cycle.matches(multi)) {
                cycle.progress = Math.max(0, multi.mProgresstime);
                return cycle.parallel;
            }
            CYCLE_PARALLELS.remove(multi);
            // GTNL's assembly line and other custom checkProcessing machines keep no ProcessingLogic value; their
            // recipe output is multiplied by the parallel count, so the output stack is the only remaining estimate.
            // Machines that do use ProcessingLogic must not fall back to it: one craft can output several items,
            // which would read as that many parallels.
            return hasProcessingLogic(multi) ? 0 : outputParallel(multi);
        }
        // A fast machine finishes and restarts between two status samples; keep the last captured cycle value for a
        // short window so the tooltip does not report unknown on every cycle boundary.
        if (cycle != null && recentlyWorked(multi)) return cycle.parallel;
        CYCLE_PARALLELS.remove(multi);
        return 0;
    }

    private static boolean hasProcessingLogic(MTEMultiBlockBase multi) {
        if (PROCESSING_LOGIC == null) return false;
        try {
            return PROCESSING_LOGIC.get(multi) != null;
        } catch (IllegalAccessException | RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean recentlyWorked(MTEMultiBlockBase multi) {
        if (RUN_TIME == null || LAST_WORK == null) return false;
        try {
            long now = RUN_TIME.getLong(multi);
            long worked = LAST_WORK.getLong(multi);
            return worked > 0 && now - worked <= IDLE_PARALLEL_GRACE_TICKS;
        } catch (IllegalAccessException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Every native recipe check begins a new cycle, even when its duration and outputs are unchanged. */
    static synchronized void recipeChecked(Object machine, boolean successful) {
        if (!(machine instanceof MTEMultiBlockBase multi)) return;
        CYCLE_PARALLELS.remove(multi);
        if (successful && multi.mMaxProgresstime > 0) rememberParallels(multi, processingParallels(multi));
    }

    /** GT's lastParallel NBT field describes the restored cycle only, not subsequent recipe checks. */
    static synchronized void recipeLoaded(Object machine) {
        if (!(machine instanceof MTEMultiBlockBase multi)) return;
        CYCLE_PARALLELS.remove(multi);
        if (multi.mMaxProgresstime <= 0 || PROCESSING_LOGIC == null) return;
        try {
            if (PROCESSING_LOGIC.get(multi) != null) rememberParallels(multi, multi.lastParallel);
        } catch (IllegalAccessException | RuntimeException unavailable) {
            // An unavailable native value stays unknown.
        }
    }

    private static long processingParallels(MTEMultiBlockBase multi) {
        try {
            if (ORE_FACTORY_TYPES.get(multi.getClass())) return Math.max(0, multi.lastParallel);
            if (PROCESSING_LOGIC == null || CURRENT_PARALLELS == null) return 0;
            Object logic = PROCESSING_LOGIC.get(multi);
            if (logic == null) return 0;
            Object value = CURRENT_PARALLELS.invoke(logic);
            return value instanceof Integer current ? Math.max(0, current) : 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    private static long outputParallel(MTEMultiBlockBase multi) {
        long parallel = 0;
        if (multi.mOutputItems != null) for (ItemStack stack : multi.mOutputItems) {
            if (stack != null) parallel = Math.max(parallel, stack.stackSize);
        }
        return parallel;
    }

    private static void rememberParallels(MTEMultiBlockBase multi, long parallel) {
        if (parallel > 0) CYCLE_PARALLELS.put(multi, new CycleParallel(multi, parallel));
    }

    private static final class CycleParallel {

        final long parallel;
        final int duration;
        final Object itemOutputs;
        final Object fluidOutputs;
        int progress;

        CycleParallel(MTEMultiBlockBase multi, long parallel) {
            this.parallel = parallel;
            duration = multi.mMaxProgresstime;
            itemOutputs = multi.mOutputItems;
            fluidOutputs = multi.mOutputFluids;
            progress = Math.max(0, multi.mProgresstime);
        }

        boolean matches(MTEMultiBlockBase multi) {
            return duration == multi.mMaxProgresstime && itemOutputs == multi.mOutputItems
                && fluidOutputs == multi.mOutputFluids
                && progress <= Math.max(0, multi.mProgresstime);
        }
    }

    static boolean openGui(TileEntity tile, net.minecraft.entity.player.EntityPlayer player) {
        if (!(tile instanceof IGregTechTileEntity)) return false;
        IMetaTileEntity machine = ((IGregTechTileEntity) tile).getMetaTileEntity();
        if (!(machine instanceof gregtech.api.metatileentity.CommonMetaTileEntity common)) return false;
        common.openGui(player);
        return true;
    }

    static String machineName(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) return null;
        IMetaTileEntity machine = ((IGregTechTileEntity) tile).getMetaTileEntity();
        if (machine == null) return null;
        String name = machine.getLocalName();
        return name == null ? machine.getMetaName() : name;
    }

    /**
     * GT's own last shutdown reason (power loss, out of fluid, structure broken...), when the machine was shut down.
     */
    private static String shutdown(IGregTechTileEntity host) {
        try {
            if (!host.wasShutdown()) return null;
            var reason = host.getLastShutDownReason();
            return reason == null ? null : reason.getDisplayString();
        } catch (RuntimeException | LinkageError olderGregTech) {
            return null;
        }
    }

    /** Unfixed maintenance problems; a controller that skips maintenance reports none. */
    private static int maintenanceIssues(MTEMultiBlockBase multi) {
        try {
            if (!multi.shouldCheckMaintenance()) return 0;
            return Math.max(0, multi.getIdealStatus() - multi.getRepairStatus());
        } catch (RuntimeException | LinkageError olderGregTech) {
            return 0;
        }
    }

    /**
     * Hatch lists resolved reflectively: their visibility differs between GT builds (mSmartInputHatches is private in
     * GTNH 2.9), and a direct field reference fails with IllegalAccessError inside the controller tick.
     */
    private static final List<Field> INPUT_LISTS = inputLists(
        "mInputBusses",
        "mInputHatches",
        "mDualInputHatches",
        "mSmartInputHatches");

    private static List<Field> inputLists(String... names) {
        List<Field> fields = new ArrayList<>();
        for (String name : names) {
            try {
                Field field = MTEMultiBlockBase.class.getDeclaredField(name);
                field.setAccessible(true);
                fields.add(field);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Absent or sealed in this GT build; observation proceeds with the remaining lists.
            }
        }
        return Collections.unmodifiableList(fields);
    }

    /** Input hatch and bus tiles of a formed multiblock controller. */
    static List<TileEntity> inputs(Object machine) {
        if (!(machine instanceof MTEMultiBlockBase)) return Collections.emptyList();
        List<TileEntity> tiles = new ArrayList<>();
        for (Field field : INPUT_LISTS) {
            Object value;
            try {
                value = field.get(machine);
            } catch (IllegalAccessException ignored) {
                continue;
            }
            if (!(value instanceof List)) continue;
            List<?> hatches = (List<?>) value;
            for (Object hatch : hatches) {
                TileEntity base = hostTile(hatch);
                if (base != null) tiles.add(base);
                // A crafting input proxy feeds from its master buffer, which is where patterns are pushed.
                TileEntity master = hostTile(craftingInputMaster(hatch));
                if (master != null) tiles.add(master);
            }
        }
        return tiles;
    }

    static TileEntity hostTile(Object machine) {
        if (!(machine instanceof IMetaTileEntity)) return null;
        IGregTechTileEntity base = ((IMetaTileEntity) machine).getBaseMetaTileEntity();
        return base instanceof TileEntity ? (TileEntity) base : null;
    }

    /** Mirror hatches linked to a pattern buffer: GT's slave list plus GTNL's watcher-registered proxies. */
    static List<TileEntity> craftingInputProxies(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity host)) return Collections.emptyList();
        IMetaTileEntity machine = host.getMetaTileEntity();
        if (machine == null) return Collections.emptyList();
        boolean buffer = CRAFTING_INPUT_BUFFERS.get(machine.getClass());
        boolean superHatch = SUPER_CRAFTING_INPUT_HATCHES.get(machine.getClass());
        if (!buffer && !superHatch) return Collections.emptyList();
        List<TileEntity> tiles = new ArrayList<>();
        if (buffer && PROXY_HATCHES != null) {
            try {
                Object proxies = PROXY_HATCHES.invoke(machine);
                if (proxies instanceof Iterable<?>list) for (Object proxy : list) {
                    TileEntity base = hostTile(proxy);
                    if (base != null && !base.isInvalid() && !tiles.contains(base)) tiles.add(base);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // Fall through to the watcher list; GTNL proxies never appear in GT's own slave list.
            }
        }
        if (WATCHERS != null) {
            try {
                Object watchers = WATCHERS.get(machine);
                if (watchers instanceof Iterable<?>list) for (Object watcher : list) {
                    if (watcher == null || !MIRROR_PROXY_TYPES.get(watcher.getClass())) continue;
                    TileEntity base = hostTile(watcher);
                    if (base != null && !base.isInvalid() && !tiles.contains(base)) tiles.add(base);
                }
            } catch (IllegalAccessException | RuntimeException | LinkageError unavailable) {
                // A GT build without the hatch watcher list simply has no GTNL proxies.
            }
        }
        return tiles;
    }

    private static Field hatchField() {
        try {
            Field field = MTEHatch.class.getDeclaredField("watchers");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static final ClassValue<Boolean> SUPER_CRAFTING_INPUT_HATCHES = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                if ("com.science.gtnl.common.machine.hatch.SuperCraftingInputHatchME".equals(c.getName())) return true;
            return false;
        }
    };

    private static final ClassValue<Boolean> MIRROR_PROXY_TYPES = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                if ("com.science.gtnl.common.machine.hatch.SuperCraftingInputProxy".equals(c.getName())) return true;
            return false;
        }
    };

    private static final ClassValue<Boolean> CRAFTING_INPUT_BUFFERS = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                if ("gregtech.common.tileentities.machines.MTEHatchCraftingInputME".equals(c.getName())) return true;
            return false;
        }
    };

    /** MTEHatchCraftingInputSlave#getMaster, resolved reflectively since the proxy class varies between GT builds. */
    private static final java.lang.reflect.Method CRAFTING_INPUT_MASTER = craftingInputMaster();

    private static java.lang.reflect.Method craftingInputMaster() {
        try {
            return Class.forName("gregtech.common.tileentities.machines.MTEHatchCraftingInputSlave")
                .getMethod("getMaster");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException absent) {
            return null;
        }
    }

    private static Object craftingInputMaster(Object hatch) {
        if (CRAFTING_INPUT_MASTER == null || !CRAFTING_INPUT_MASTER.getDeclaringClass()
            .isInstance(hatch)) return null;
        try {
            return CRAFTING_INPUT_MASTER.invoke(hatch);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    static NativeGregTech.MachineSnapshot inspect(TileEntity tile) {
        if (!(tile instanceof IGregTechTileEntity)) return null;
        IGregTechTileEntity host = (IGregTechTileEntity) tile;
        var machine = host.getMetaTileEntity();
        if (!(machine instanceof MTEBasicMachine)) return null;
        MTEBasicMachine basic = (MTEBasicMachine) machine;
        return new NativeGregTech.MachineSnapshot(
            host.getMetaTileID(),
            machine.getClass()
                .getName(),
            host.isActive(),
            host.getStoredEU(),
            host.getEUCapacity(),
            basic.mProgresstime,
            basic.mMaxProgresstime,
            basic.mEUt);
    }
}
