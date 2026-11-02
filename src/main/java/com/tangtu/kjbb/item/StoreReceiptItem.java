package com.tangtu.kjbb.item;

import com.tangtu.kjbb.net.StoreFormNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 仓单：只做【记录】——记下某个容器里有什么、有多少，不转移货物、不兑付。
 *
 * NBT：
 *   kjbb_pos     "x,y,z"
 *   kjbb_owner   开具人
 *   kjbb_time    开具时间（游戏刻）
 *   kjbb_items   ListTag[ {id, count} ]
 */
public class StoreReceiptItem extends Item {

    public StoreReceiptItem(Properties props) {
        super(props);
    }

    /** 一条记录 */
    public record Line(String itemId, int count) {
    }

    /** 每张仓单的唯一编号（拆分会生成新号） */
    public static long rid(ItemStack stack) {
        var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        long v = 0L;
        if (cd != null) {
            var tag = cd.copyTag();
            if (tag.contains("kjbb_rid")) {
                v = tag.getLong("kjbb_rid");
            }
        }
        if (v == 0L) {
            v = newId();
            setRid(stack, v);
        }
        return v;
    }

    public static void setRid(ItemStack stack, long rid) {
        var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        var tag = cd == null ? new net.minecraft.nbt.CompoundTag() : cd.copyTag();
        tag.putLong("kjbb_rid", rid);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
    }

    private static long newId() {
        long v = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        if (v < 0) {
            v = -v;
        }
        return v == 0 ? 1 : v;
    }

    /** 仓单所属维度（老仓单没有这个字段 → 返回空串） */
    public static String dim(ItemStack stack) {
        var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd == null) {
            return "";
        }
        var tag = cd.copyTag();
        return tag.contains("kjbb_dim") ? tag.getString("kjbb_dim") : "";
    }

    public static void setDim(ItemStack stack, String dim) {
        var cd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        var tag = cd == null ? new net.minecraft.nbt.CompoundTag() : cd.copyTag();
        tag.putString("kjbb_dim", dim == null ? "" : dim);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
    }

    /** 单品种仓单的物品 id（多品种返回 null） */
    public static String singleItemId(ItemStack stack) {
        var list = readRecord(stack);
        if (list.size() != 1) {
            return null;
        }
        return list.get(0).itemId();
    }

    /** 单品种仓单的数量（多品种返回 0） */
    public static int singleCount(ItemStack stack) {
        var list = readRecord(stack);
        if (list.size() != 1) {
            return 0;
        }
        return list.get(0).count();
    }

    /** 生成一张「单品种、指定数量」的仓单（复制其它标记：位置/记名/开具人） */
    public static ItemStack withSingle(ItemStack src, String itemId, int count, String owner,
                                       long time) {
        ItemStack out = new ItemStack(KjbbItems.STORE_RECEIPT.get());
        String posText = posText(src);
        net.minecraft.core.BlockPos pos;
        try {
            String[] f = posText.split(",");
            pos = new net.minecraft.core.BlockPos(Integer.parseInt(f[0].trim()),
                    Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()));
        } catch (Throwable t) {
            pos = new net.minecraft.core.BlockPos(0, 0, 0);
        }
        java.util.List<Line> lines = new java.util.ArrayList<>();
        lines.add(new Line(itemId, count));
        writeRecord(out, pos, owner, time, lines);
        setBearer(out, isBearer(src));
        setDim(out, dim(src));
        return out;
    }

    /** 这张仓单是不是记录了这个位置 */
    public static boolean recordsPos(ItemStack stack, net.minecraft.core.BlockPos pos) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String want = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        return want.equals(posText(stack));
    }

    /** 容器被摧毁后仓单作废 */
    public static boolean isVoid(ItemStack stack) {
        CompoundTag tag = recordTag(stack);
        return tag.contains("kjbb_void") && tag.getBoolean("kjbb_void");
    }

    public static void setVoid(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putBoolean("kjbb_void", true));
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal("仓单（已作废）").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 不记名（凭票认人） */
    public static boolean isBearer(ItemStack stack) {
        CompoundTag tag = recordTag(stack);
        return tag.contains("kjbb_bearer") && tag.getBoolean("kjbb_bearer");
    }

    public static void setBearer(ItemStack stack, boolean bearer) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putBoolean("kjbb_bearer", bearer));
    }

    public static void writeRecord(ItemStack stack, BlockPos pos, String owner, long time,
                                   List<Line> lines) {
        writeRecord(stack, pos, onlineDim(owner), owner, time, lines);
    }

    public static void writeRecord(ItemStack stack, BlockPos pos, String dim, String owner, long time,
                                   List<Line> lines) {
        ListTag list = new ListTag();
        for (Line l : lines) {
            CompoundTag c = new CompoundTag();
            c.putString("id", l.itemId());
            c.putInt("count", l.count());
            list.add(c);
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            String base = pos.getX() + "," + pos.getY() + "," + pos.getZ();
            tag.putString("kjbb_pos", dim == null || dim.isEmpty() ? base : (dim + "#" + base));
            tag.putString("kjbb_dim", dim == null ? "" : dim);
            tag.putString("kjbb_owner", owner);
            tag.putLong("kjbb_time", time);
            tag.put("kjbb_items", list);
        });
    }

    private static CompoundTag recordTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    public static String posText(ItemStack stack) {
        return recordTag(stack).getString("kjbb_pos");
    }

    /** 按持有人当前所在维度推断（离线则为空） */
    private static String onlineDim(String player) {
        try {
            var srv = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null || player == null) {
                return "";
            }
            var sp = srv.getPlayerList().getPlayerByName(player);
            if (sp == null) {
                return "";
            }
            return sp.serverLevel().dimension().location().toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 仓单记录的维度（老仓单为空，按主世界处理） */
    public static String dimText(ItemStack stack) {
        return recordTag(stack).getString("kjbb_dim");
    }

    /** 纯坐标部分（去掉维度前缀） */
    public static String coordText(ItemStack stack) {
        String p = posText(stack);
        int i = p.indexOf('#');
        if (i >= 0) {
            return p.substring(i + 1);
        }
        i = p.indexOf('|');            // 兼容旧写法
        return i >= 0 ? p.substring(i + 1) : p;
    }

    public static String ownerText(ItemStack stack) {
        return recordTag(stack).getString("kjbb_owner");
    }

    public static List<Line> readRecord(ItemStack stack) {
        List<Line> out = new ArrayList<>();
        CompoundTag tag = recordTag(stack);
        if (!tag.contains("kjbb_items", Tag.TAG_LIST)) {
            return out;
        }
        ListTag list = tag.getList("kjbb_items", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            out.add(new Line(c.getString("id"), c.getInt("count")));
        }
        return out;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> lines, TooltipFlag flag) {
        String pos = posText(stack);
        if (isVoid(stack)) {
            lines.add(Component.literal("容器已被摧毁，货物已转入虚拟仓")
                    .withStyle(ChatFormatting.RED));
        }
        lines.add(Component.literal("仓库记录").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(isBearer(stack) ? "类型：不记名"
                : "类型：记名").withStyle(ChatFormatting.GOLD));
        if (!pos.isEmpty()) {
            lines.add(Component.literal("位置：" + pos).withStyle(ChatFormatting.DARK_GRAY));
        }
        List<Line> rec = readRecord(stack);
        int shown = 0;
        for (Line l : rec) {
            if (shown >= 5) {
                break;
            }
            lines.add(Component.literal("· " + niceName(l.itemId()) + " ×" + l.count())
                    .withStyle(ChatFormatting.DARK_AQUA));
            shown++;
        }
        if (rec.size() > shown) {
            lines.add(Component.literal("…还有 " + (rec.size() - shown) + " 种")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.literal("右键查看全部").withStyle(ChatFormatting.GRAY));
    }

    public static String niceName(String itemId) {
        try {
            Item it = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            if (it == null) {
                return itemId;
            }
            return new ItemStack(it).getHoverName().getString();
        } catch (Throwable t) {
            return itemId;
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            // 恢复：右键打开仓单查看界面（界面里再点「挂卖」）
            StoreFormNetwork.sendReceiptView(sp, stack);
        }
        return InteractionResultHolder.success(stack);
    }

    /** 把容器内容做成一条记录（供网络层调用） */
    public static List<Line> linesOf(Map<String, Integer> counts) {
        List<Line> out = new ArrayList<>();
        for (var e : counts.entrySet()) {
            out.add(new Line(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 生成一张仓单物品 */
    public static ItemStack create(BlockPos pos, String owner, long time,
                                   Map<String, Integer> counts) {
        ItemStack stack = new ItemStack(KjbbItems.STORE_RECEIPT.get());
        writeRecord(stack, pos, owner, time, linesOf(counts));
        return stack;
    }

    @SuppressWarnings("unused")
    private static StringTag unusedKeepImport() {
        return StringTag.valueOf("");
    }
}
