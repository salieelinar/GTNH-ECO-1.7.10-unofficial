package cn.dancingsnow.neoecoae.platform.ae2.crafting;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.jetbrains.annotations.Nullable;

import com.glodblock.github.common.tile.TileFluidAutoFiller;

import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.IInterfaceViewable;
import appeng.container.ContainerNull;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.parts.AEBasePart;
import appeng.util.inv.MEInventoryCrafting;
import cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target;
import cn.dancingsnow.neoecoae.api.me.provider.ECOIndeterminateBatchException;
import cn.dancingsnow.neoecoae.blocks.entity.LargeWorkstationPatternProvider;
import cn.dancingsnow.neoecoae.compat.proghatches.ProgrammableHatchCrafting;
import cn.dancingsnow.neoecoae.platform.ae2.key.AEKey;
import cn.dancingsnow.neoecoae.platform.ae2.key.GenericStack;
import cn.dancingsnow.neoecoae.platform.ae2.key.KeyCounter;
import cn.dancingsnow.neoecoae.platform.gt.NativeGregTech;
import cn.dancingsnow.neoecoae.platform.minecraft.BlockPos;

/** Real native medium dispatch, retaining GTNH's generic item/fluid crafting table. */
public final class NativeCraftingProvider implements ICraftingProvider, NativeProviderAdapters.BlockingState,
    cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider {

    private final ICraftingMedium medium;
    private final List<IPatternDetails> patterns;
    private static final org.apache.logging.log4j.Logger COMPAT_LOGGER = org.apache.logging.log4j.LogManager
        .getLogger("neoecoae.compat");
    private static final java.util.Set<String> LOGGED_COMPAT_MEDIA = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static ICraftingProvider wrap(ICraftingMedium medium, List<? extends IPatternDetails> patterns) {
        if (medium instanceof NativeSourceCraftingMedium) return java.util.Objects
            .requireNonNull(((NativeSourceCraftingMedium) medium).sourceCraftingProvider(), "Native ECO provider");
        return new NativeCraftingProvider(medium, patterns);
    }

    public NativeCraftingProvider(ICraftingMedium medium, List<? extends IPatternDetails> patterns) {
        this.medium = java.util.Objects.requireNonNull(medium);
        this.patterns = Collections.unmodifiableList(new ArrayList<>(patterns));
        this.unboundedPatternHatch = UNBOUNDED_HATCH_TYPES.get(medium.getClass());
        this.assemblerMatrix = ASSEMBLER_MATRIX_TYPES.get(medium.getClass());
        String mediumName = medium.getClass()
            .getName();
        // Both GTSWN endpoints are final. Match the optional medium once without loading either class.
        this.preserveInputSlots = assemblerMatrix || GTSWN_ITEM_INPUT.equals(mediumName)
            || GTSWN_FLUID_INPUT.equals(mediumName);
        if ((mediumName.startsWith("com.science.gtnl.") || mediumName.startsWith("com.tenkeitech."))
            && LOGGED_COMPAT_MEDIA.add(mediumName)) {
            COMPAT_LOGGER.info("[ECO-COMPAT] medium={} unbounded={}", mediumName, unboundedPatternHatch);
        }
    }

    public ICraftingMedium nativeMedium() {
        return medium;
    }

    /** Physical pattern holder, including the face of a cable-mounted interface. Never probes a push target. */
    @Nullable
    public static Target patternProviderTarget(Object provider) {
        if (provider instanceof NativeCraftingProvider nativeProvider) provider = nativeProvider.nativeMedium();
        if (provider instanceof LargeWorkstationPatternProvider workstation)
            provider = workstation.getWorkstationInterface();
        // DualityInterface#getTile is null for parts; its host owns the actual cable tile and part face.
        if (provider instanceof DualityInterface duality) provider = duality.getHost();
        ForgeDirection side = provider instanceof AEBasePart part ? part.getSide() : null;
        TileEntity tile = provider instanceof IInterfaceViewable viewable ? viewable.getTileEntity()
            : provider instanceof TileEntity direct ? direct : NativeGregTech.hostTile(provider);
        if (tile == null || tile.isInvalid() || tile.getWorldObj() == null) return null;
        return new Target(
            tile.getWorldObj().provider.dimensionId,
            new BlockPos(tile.xCoord, tile.yCoord, tile.zCoord),
            side);
    }

    @Override
    public cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target neoecoae$getProcessingTarget() {
        return medium instanceof cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider
            ? ((cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider) medium)
                .neoecoae$getProcessingTarget()
            : null;
    }

    @Override
    public java.util.List<cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target> neoecoae$getProcessingTargets() {
        return processingTargets(medium);
    }

    /** Destinations of the last accepted push of a raw native medium; same rules as a wrapped provider. */
    public static java.util.List<cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target> processingTargets(
        ICraftingMedium medium) {
        if (medium instanceof cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider)
            return ((cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider) medium)
                .neoecoae$getProcessingTargets();
        if (medium instanceof IInterfaceHost host) return processingTargets(host.getInterfaceDuality());
        if (medium instanceof NativeSourceCraftingMedium) {
            var source = ((NativeSourceCraftingMedium) medium).sourceCraftingProvider();
            return source instanceof cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider
                ? ((cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider) source)
                    .neoecoae$getProcessingTargets()
                : ownPosition(medium);
        }
        return ownPosition(medium);
    }

    /**
     * A medium that is itself the destination (GT's ME crafting input bus/buffer, other pattern hatches) records no
     * push targets; its own block is where the inputs went, and the status scan resolves its controllers from there.
     */
    private static java.util.List<cn.dancingsnow.neoecoae.api.me.diagnostics.ECOProcessingTargetProvider.Target> ownPosition(
        ICraftingMedium medium) {
        Target target = patternProviderTarget(medium);
        return target == null ? Collections.emptyList()
            : Collections.singletonList(new Target(target.dimension(), target.position()));
    }

    @Override
    public int getPatternPriority() {
        int priority = Integer.MIN_VALUE;
        for (var pattern : patterns) if (pattern instanceof NativePatternDetails) priority = Math.max(
            priority,
            ((NativePatternDetails) pattern).nativeDetails()
                .getPriority());
        return priority == Integer.MIN_VALUE ? 0 : priority;
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    @Override
    public boolean isBusy() {
        return medium.isBusy();
    }

    @Override
    public boolean isBlocking() {
        return medium.getBlockingMode() != ICraftingMedium.BlockingMode.NONE;
    }

    /**
     * GT's ME crafting input hatches, and AE2 interfaces of every host (block, part, AE2FC dual interface, P2P
     * interface tunnel). An interface with any crafting-lock mode keeps one copy per push: pulse/redstone locks meter
     * pushes on purpose, and LOCK_UNTIL_RESULT unlocks on one copy's outputs.
     */
    public boolean supportsScaledProcessing() {
        // AE2FC's auto filler ignores the input table and emits one pattern output per push.
        if (medium instanceof TileFluidAutoFiller) return false;
        // A host whose user switch disables pattern optimization is dispatched one copy at a time.
        if (strictSerialMode()) return false;
        // Host media (e.g. GTNL's assembler matrix) expose their duality; the optimizer switch lives there.
        DualityInterface duality = medium instanceof DualityInterface direct ? direct
            : medium instanceof IInterfaceHost host ? host.getInterfaceDuality() : null;
        // Programmable Hatches' pattern hatch is recognised by its own class contract; every other medium defaults
        // to eligible, exactly as AE2's own batching does.
        return ProgrammableHatchCrafting.supportsScaledProcessing(medium)
            && (duality == null || (duality.getConfigManager()
                .getSetting(appeng.api.config.Settings.PATTERN_OPTIMIZATION) == appeng.api.config.YesNo.YES
                && !hasCraftingLock(duality)));
    }

    /**
     * The host interface's pattern-optimization switch, read live. Strict serial dispatch only applies to hosts
     * that own a user switch: AE2 interfaces (block, part, AE2FC dual interface) and GTNL's super pattern-input
     * hatch. GT's own crafting input hatch has no switch (GTNL's mixin keeps the default true), and GTNL's
     * assembler matrix always reports false on purpose to exclude itself from AE2's optimizer, so neither is
     * treated as a strict host.
     */
    public boolean strictSerialMode() {
        if (medium instanceof appeng.helpers.DualityInterface duality) {
            return duality.getHost() instanceof IInterfaceViewable host && !host.allowsPatternOptimization();
        }
        return medium instanceof IInterfaceViewable viewable && GTNL_SUPER_PATTERN_HATCH.get(viewable.getClass())
            && !viewable.allowsPatternOptimization();
    }

    static boolean hasCraftingLock(appeng.helpers.DualityInterface medium) {
        return isCraftingLocked(medium.getConfigManager());
    }

    /** Any configured LOCK_CRAFTING_MODE other than NONE, whether or not it is currently holding the interface. */
    public static boolean isCraftingLocked(appeng.api.util.IConfigManager config) {
        if (config == null) return false;
        var mode = config.getSetting(appeng.api.config.Settings.LOCK_CRAFTING_MODE);
        return mode != null && mode != appeng.api.config.LockCraftingMode.NONE;
    }

    /**
     * Smart doubling for blocking interfaces: AE2's own pushPattern still decides whether the target counts as empty
     * (advanced blocking card, GT ignored items, smart-blocking hash), ECO only sizes the single push.
     */
    public boolean allowsScaledWhileBlocking() {
        return supportsScaledProcessing();
    }

    /** Plain blocking accepts one push per empty target; smart blocking keeps accepting the same pattern. */
    public boolean isSinglePushPerVisit() {
        return medium.getBlockingMode() == ICraftingMedium.BlockingMode.BLOCKING;
    }

    /**
     * Whether the last rejected push was refused because the target was occupied or locked. Such a rejection says
     * nothing about how many copies fit, so it must not shrink the learned batch size.
     */
    public boolean wasRejectedByBlocking() {
        var reason = medium.getScheduledReason();
        return reason == appeng.util.ScheduledReason.BLOCKING_MODE || reason == appeng.util.ScheduledReason.LOCK_MODE;
    }

    /**
     * GT's ME crafting input bus/buffer (and subclasses), GTNL's super pattern input hatch, and TenkeiTech's super /
     * auto-template input hatches: never busy, and every push is stored whole in the pattern's internal inventory, so
     * one push may carry any number of copies.
     */
    public boolean isUnboundedPatternHatch() {
        return unboundedPatternHatch;
    }

    /** Mirrors GTNH CraftingCPUCluster: only a DualityInterface medium carrying the fake crafting card qualifies. */
    public boolean isFakeCraftingMode() {
        return medium instanceof appeng.helpers.DualityInterface
            && ((appeng.helpers.DualityInterface) medium).isFakeCraftingMode();
    }

    /**
     * GT's hatch stores any long amount by splitting it into Integer.MAX_VALUE-sized stacks (GT 5.09.54.264
     * {@code PatternSlot.insertItem/insertFluid}), so its real bound is the saved stack list, not a copy count.
     * One push may add at most this many split stacks per resource.
     */
    public static final long UNBOUNDED_HATCH_STACKS_PER_PUSH = 16L;

    /** Copies one hatch push may carry for these one-copy inputs; never below one copy. */
    public static long unboundedHatchCopyLimit(KeyCounter[] perCraftInputs) {
        var totals = new java.util.HashMap<AEKey, Long>();
        try {
            for (var counter : perCraftInputs) for (var entry : counter) {
                if (entry.getLongValue() > 0) totals.merge(entry.getKey(), entry.getLongValue(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return 1L;
        }
        long perResource = UNBOUNDED_HATCH_STACKS_PER_PUSH * Integer.MAX_VALUE;
        long limit = Long.MAX_VALUE;
        for (long amount : totals.values()) limit = Math.min(limit, perResource / amount);
        return Math.max(1L, limit);
    }

    /** Unknown media may read int-sized item/fluid views, so bound aggregate amounts before probing. */
    public long scaledProcessingCopyLimit(KeyCounter[] perCraftInputs) {
        var totals = new java.util.HashMap<AEKey, Long>();
        try {
            for (var counter : perCraftInputs) for (var entry : counter) {
                if (entry.getLongValue() > 0) totals.merge(entry.getKey(), entry.getLongValue(), Math::addExact);
            }
        } catch (ArithmeticException overflow) {
            return 1L;
        }
        long limit = Long.MAX_VALUE;
        for (long amount : totals.values()) limit = Math.min(limit, Integer.MAX_VALUE / amount);
        return Math.max(1L, limit);
    }

    /** Successful native pushes drive the probe; AE2 additionally exposes unfinished outgoing transfers. */
    public boolean isScaledProcessingFullyInserted() {
        return !(medium instanceof appeng.helpers.DualityInterface)
            || NativeProcessingAccess.isSendBufferEmpty((appeng.helpers.DualityInterface) medium);
    }

    private static final String GT_CRAFTING_INPUT_HATCH = "gregtech.common.tileentities.machines.MTEHatchCraftingInputME";
    /**
     * GTNL's super pattern input hatch is not a {@code MTEHatchCraftingInputME} subclass, but it stores every pushed
     * stack whole in its per-pattern inventory (splitting above {@link Integer#MAX_VALUE}), exactly like GT's hatch.
     */
    private static final String GTNL_CRAFTING_INPUT_HATCH = "com.science.gtnl.common.machine.hatch.SuperCraftingInputHatchME";
    /**
     * TenkeiTech's super and auto-template input hatches use long-backed per-pattern / shared input stores. The
     * bender and wiremill variants extend the auto-template hatch, so the superclass check covers them too.
     */
    private static final String TENKEI_AUTO_TEMPLATE_INPUT_HATCH = "com.tenkeitech.machine.MTEHatchAutoTemplateInputME";
    private static final String TENKEI_SUPER_CRAFTING_INPUT_HATCH = "com.tenkeitech.machine.MTESuperCraftingInputME";
    private static final String GTNL_ASSEMBLER_MATRIX = "com.science.gtnl.common.machine.multiblock.AssemblerMatrix";
    private static final String GTSWN_ITEM_INPUT = "com.miaokatze.gtswn.common.assembly.MatrixItemInputBus";
    private static final String GTSWN_FLUID_INPUT = "com.miaokatze.gtswn.common.assembly.MatrixFluidInputHatch";
    private static final String GTNL_SUPER_PATTERN_HATCH_NAME = "com.science.gtnl.common.machine.hatch.SuperCraftingInputHatchME";
    private static final ClassValue<Boolean> GTNL_SUPER_PATTERN_HATCH = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                if (GTNL_SUPER_PATTERN_HATCH_NAME.equals(c.getName())) return true;
            return false;
        }
    };
    private static final ClassValue<Boolean> UNBOUNDED_HATCH_TYPES = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                String name = c.getName();
                if (GT_CRAFTING_INPUT_HATCH.equals(name) || GTNL_CRAFTING_INPUT_HATCH.equals(name)
                    || TENKEI_AUTO_TEMPLATE_INPUT_HATCH.equals(name)
                    || TENKEI_SUPER_CRAFTING_INPUT_HATCH.equals(name)
                    || GTNL_ASSEMBLER_MATRIX.equals(name)) return true;
            }
            return false;
        }
    };
    private static final ClassValue<Boolean> ASSEMBLER_MATRIX_TYPES = new ClassValue<>() {

        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null; c = c.getSuperclass())
                if (GTNL_ASSEMBLER_MATRIX.equals(c.getName())) return true;
            return false;
        }
    };
    private static final ClassValue<AssemblerBatchAccess> ASSEMBLER_BATCH_ACCESS = new ClassValue<>() {

        @Override
        protected AssemblerBatchAccess computeValue(Class<?> type) {
            try {
                Method setter = type.getMethod("setAssemblerSize", long.class);
                Method getter = type.getMethod("getAssemblerSize");
                return setter.getReturnType() == void.class && getter.getReturnType() == long.class
                    ? new AssemblerBatchAccess(setter, getter)
                    : new AssemblerBatchAccess(null, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return new AssemblerBatchAccess(null, null);
            }
        }
    };
    private final boolean unboundedPatternHatch;
    /** GTNL's assembler matrix reads its batch multiplier from the crafting inventory, not from stack sizes. */
    private final boolean assemblerMatrix;
    /** GTNL resolves container returns, GTSWN ordered processing: both need the physical pattern's slots. */
    private final boolean preserveInputSlots;

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputHolder) {
        IPatternDetails physical = pattern instanceof cn.dancingsnow.neoecoae.compat.ae2.ECOProcessingExecutionPattern
            ? ((cn.dancingsnow.neoecoae.compat.ae2.ECOProcessingExecutionPattern) pattern).providerLookupPattern()
            : pattern;
        if (!(physical instanceof NativePatternDetails) || !patterns.contains(physical)) return false;
        boolean batchableView = physical instanceof AEProcessingPattern
            || physical instanceof NativePatternDetails details && (unboundedPatternHatch && !details.isCraftable()
                || NativePatternDetails.isTenkeiTechProcessingPattern(details.nativeDetails())
                || unboundedPatternHatch && NativePatternDetails.isFluidProcessingPattern(details.nativeDetails()));
        // Upstream's plain-processing proof, plus the fork's unbounded/third-party views. A batched table is only
        // built for media that accept the scaled submission contract.
        if (physical != pattern
            && (!supportsScaledProcessing() || !(batchableView || physical.supportsPushInputsToExternalInventory())))
            return false;
        var nativePattern = (NativePatternDetails) physical;
        if (inputHolder.length != pattern.getInputs().length)
            throw new IllegalArgumentException("Source input dimensions differ from native pattern");
        long copies = pattern instanceof cn.dancingsnow.neoecoae.compat.ae2.ECOProcessingExecutionPattern execution
            ? execution.copies()
            : 1L;
        MEInventoryCrafting table;
        if (pattern instanceof NativeCraftingPattern) {
            table = new MEInventoryCrafting(new ContainerNull(), 3, 3);
            KeyCounter[] copy = new KeyCounter[inputHolder.length];
            for (int i = 0; i < copy.length; i++) copy[i] = inputHolder[i].copy();
            ((NativeCraftingPattern) pattern).fillCraftingGrid(
                copy,
                (slot, stack) -> table.setInventorySlotContents(slot, appeng.util.item.AEItemStack.create(stack)));
            for (var counter : copy)
                if (!counter.isEmpty()) throw new IllegalArgumentException("Unexpected native crafting input surplus");
        } else if (preserveInputSlots) {
            // GTNL resolves container returns, GTSWN ordered processing: both read the physical pattern's slots.
            table = orderedProcessingTable(nativePattern, inputHolder, copies);
            if (table == null) return false;
        } else {
            var stacks = new ArrayList<IAEStack<?>>();
            for (var counter : inputHolder) for (var entry : counter) {
                if (entry.getLongValue() <= 0) throw new IllegalArgumentException("Nonpositive native dispatch input");
                var stack = entry.getKey()
                    .toNative(entry.getLongValue());
                if (stack == null)
                    throw new IllegalArgumentException("Resource has no native dispatch channel: " + entry.getKey());
                stacks.add(stack);
            }
            if (stacks.isEmpty()) {
                // Input-less Programmable Hatches patterns receive an empty grid, like native MEInvDummy.
                if (pattern.getInputs().length > 0
                    || !NativePatternDetails.mightHaveEmptyInputs(nativePattern.nativeDetails())) return false;
                table = new MEInventoryCrafting(new ContainerNull(), 3, 3);
            } else {
                table = new MEInventoryCrafting(new ContainerNull(), stacks.size(), 1);
                for (int i = 0; i < stacks.size(); i++) table.setInventorySlotContents(i, stacks.get(i));
            }
        }
        var before = new ArrayList<IAEStack<?>>();
        var beforeItems = new ArrayList<ItemStack>();
        for (int i = 0; i < table.getSizeInventory(); i++) {
            var stack = table.getAEStackInSlot(i);
            before.add(stack == null ? null : stack.copy());
            beforeItems.add(cn.dancingsnow.neoecoae.platform.minecraft.ItemStacks.copy(table.getStackInSlot(i)));
        }
        if (assemblerMatrix && !applyAssemblerBatchSize(table, copies)) return false;
        boolean accepted;
        try {
            accepted = medium.pushPattern(nativePattern.nativeDetails(), table);
        } catch (RuntimeException failure) {
            throw new ECOIndeterminateBatchException(
                "Native medium ownership is uncertain after dispatch failure",
                failure);
        }
        if (!accepted) for (int i = 0; i < table.getSizeInventory(); i++) {
            var previous = before.get(i);
            var current = table.getAEStackInSlot(i);
            boolean same = previous == null ? current == null
                : current != null && previous.getStackSize() == current.getStackSize()
                    && java.util.Objects.equals(AEKey.fromNative(previous), AEKey.fromNative(current));
            if (!same || !ItemStack.areItemStacksEqual(beforeItems.get(i), table.getStackInSlot(i)))
                throw new ECOIndeterminateBatchException("Native medium mutated rejected inputs", null);
        }
        if (accepted) for (var counter : inputHolder) counter.clear();
        return accepted;
    }

    /** Restore the physical slots for GTNL container returns and GTSWN ordered processing. */
    @Nullable
    private static MEInventoryCrafting orderedProcessingTable(NativePatternDetails pattern, KeyCounter[] inputHolder,
        long copies) {
        var remaining = new KeyCounter();
        try {
            for (var counter : inputHolder) for (var entry : counter) {
                if (entry.getLongValue() <= 0) throw new IllegalArgumentException("Nonpositive native dispatch input");
                remaining.set(entry.getKey(), Math.addExact(remaining.get(entry.getKey()), entry.getLongValue()));
            }
            GenericStack[] sparse = pattern.getSparseInputs();
            var table = new MEInventoryCrafting(new ContainerNull(), Math.max(1, sparse.length), 1);
            for (int slot = 0; slot < sparse.length; slot++) {
                GenericStack input = sparse[slot];
                if (input == null || input.amount() <= 0L) continue;
                long amount = Math.multiplyExact(input.amount(), copies);
                if (remaining.get(input.what()) < amount) return null;
                var stack = input.what()
                    .toNative(amount);
                if (stack == null) return null;
                table.setInventorySlotContents(slot, stack);
                remaining.remove(input.what(), amount);
            }
            return remaining.isEmpty() ? table : null;
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    /**
     * Refuses an unverified multiplier before the medium can claim any of the extracted inputs: without the long batch
     * bridge only the native one-copy path may pass.
     */
    private static boolean applyAssemblerBatchSize(MEInventoryCrafting table, long copies) {
        AssemblerBatchAccess access = ASSEMBLER_BATCH_ACCESS.get(table.getClass());
        if (access.setter() == null || access.getter() == null) return copies == 1L;
        try {
            access.setter()
                .invoke(table, copies);
            return Long.valueOf(copies)
                .equals(
                    access.getter()
                        .invoke(table));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    @com.github.bsideup.jabel.Desugar
    private record AssemblerBatchAccess(Method setter, Method getter) {}
}
