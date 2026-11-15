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
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** Native API linkage is isolated from the no-GT load path. */
final class GregTechMachineAccess {

    /** Last positive parallel seen for a machine cycle; the cycle's max progress identifies it. */
    private static final Map<MTEMultiBlockBase, long[]> LAST_PARALLEL = Collections
        .synchronizedMap(new WeakHashMap<>());

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
     * GT's own current parallel for the accepted recipe. {@code lastParallel} is only a legacy NBT field and is
     * never updated by normal GT multiblocks; GTNL's custom assembly line also does not use ProcessingLogic, so fall
     * back to its total output stack size (the recipe output is multiplied by the parallel count).
     * <p>
     * GT clears {@code ProcessingLogic.calculatedParallels} from {@code checkProcessing -> setupProcessingLogic} on
     * every recipe check, so a status sample can land in the gap and read zero while the cycle still runs. Keep the
     * last positive value for the current cycle ({@code mMaxProgresstime} identifies it) instead of falling back to
     * 1 mid-recipe; batch-driven machines such as the component assembly line need this.
     */
    private static long currentParallels(MTEMultiBlockBase multi) {
        long logic = processingLogicParallel(multi);
        if (logic > 0) {
            remember(multi, logic);
            return logic;
        }
        long outputs = outputParallel(multi);
        if (outputs > 0) {
            remember(multi, outputs);
            return outputs;
        }
        long[] cached = LAST_PARALLEL.get(multi);
        if (multi.mMaxProgresstime > 0 && cached != null && cached[0] == multi.mMaxProgresstime) return cached[1];
        if (multi.mMaxProgresstime <= 0) LAST_PARALLEL.remove(multi);
        return Math.max(1, multi.lastParallel);
    }

    private static long processingLogicParallel(MTEMultiBlockBase multi) {
        try {
            Field field = MTEMultiBlockBase.class.getDeclaredField("processingLogic");
            field.setAccessible(true);
            Object logic = field.get(multi);
            if (logic != null) {
                Method method = logic.getClass()
                    .getMethod("getCurrentParallels");
                int current = (Integer) method.invoke(logic);
                if (current > 0) return current;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older GT build or a machine with no ProcessingLogic; use the output-size fallback below.
        }
        return 0;
    }

    private static long outputParallel(MTEMultiBlockBase multi) {
        long parallel = 0;
        if (multi.mOutputItems != null) for (ItemStack stack : multi.mOutputItems) {
            if (stack != null) parallel = Math.max(parallel, stack.stackSize);
        }
        return parallel;
    }

    private static void remember(MTEMultiBlockBase multi, long parallel) {
        if (multi.mMaxProgresstime > 0) LAST_PARALLEL.put(multi, new long[] { multi.mMaxProgresstime, parallel });
        else LAST_PARALLEL.remove(multi);
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
