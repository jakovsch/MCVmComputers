package mcvmcomputers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import io.netty.buffer.Unpooled;
import mcvmcomputers.entities.EntityList;
import mcvmcomputers.entities.EntityPC;
import mcvmcomputers.item.ItemHarddrive;
import mcvmcomputers.item.ItemList;
import mcvmcomputers.item.OrderableItem;
import mcvmcomputers.networking.PacketList;
import mcvmcomputers.sound.SoundList;
import mcvmcomputers.utils.TabletOrder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry;
import net.fabricmc.fabric.api.server.PlayerStream;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.PacketByteBuf;

import static mcvmcomputers.networking.PacketList.*;

public class MainMod implements ModInitializer{
	public static Map<UUID, TabletOrder> orders;
	public static Map<UUID, EntityPC> computers;
	
	public static Runnable hardDriveClick = new Runnable() { @Override public void run() {} };
	public static Runnable deliveryChestSound = new Runnable() { @Override public void run() {} };
	public static Runnable focus = new Runnable() { @Override public void run() {} };
	public static Runnable pcOpenGui = new Runnable() { @Override public void run() {} };
	
	public void onInitialize() {
		orders = new HashMap<UUID, TabletOrder>();
		computers = new HashMap<UUID, EntityPC>();
		ItemList.init();
		EntityList.init();
		SoundList.init();
		registerServerPackets();
	}
	
	public static void registerServerPackets() {
		ServerSidePacketRegistry.INSTANCE.register(C2S_ORDER, (packetContext, attachedData) -> {
			int arraySize = attachedData.readInt();
			OrderableItem[] items = new OrderableItem[arraySize];
			int price = 0;
			for(int i = 0;i<arraySize;i++) {
				items[i] = (OrderableItem) attachedData.readItemStack().getItem();
				price += items[i].getPrice();
			}
			
			final int pr = price;
			
			packetContext.getTaskQueue().execute(() -> {
				TabletOrder to = new TabletOrder();
				to.items = new ArrayList<>();
				to.items.addAll(Arrays.asList(items));
				to.price = pr;
				to.orderUUID = packetContext.getPlayer().getUuid().toString();
				MainMod.orders.put(packetContext.getPlayer().getUuid(), to);
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_SCREEN, (packetContext, attachedData) -> {
			byte[] screen = attachedData.readByteArray();
			int compressedDataSize = attachedData.readInt();
			int dataSize = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				if(MainMod.computers.containsKey(packetContext.getPlayer().getUuid())) {
					Stream<PlayerEntity> watchingPlayers = PlayerStream.watching(MainMod.computers.get(packetContext.getPlayer().getUuid()));
					PacketByteBuf b = new PacketByteBuf(Unpooled.buffer());
					b.writeByteArray(screen);
					b.writeInt(compressedDataSize);
					b.writeInt(dataSize);
					b.writeUuid(packetContext.getPlayer().getUuid());
					watchingPlayers.forEach((player) -> {
						ServerSidePacketRegistry.INSTANCE.sendToPlayer(player, S2C_SCREEN, b);
					});
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_TURN_ON_PC, (packetContext, attachedData) -> {
			int pcEntityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(pcEntityId);
				if(e != null) {
					if(e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							MainMod.computers.put(packetContext.getPlayer().getUuid(), (EntityPC) e);
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_TURN_OFF_PC, (packetContext, attachedData) -> {
			packetContext.getTaskQueue().execute(() -> {
				Stream<PlayerEntity> watchingPlayers = PlayerStream.watching(MainMod.computers.get(packetContext.getPlayer().getUuid()));
				PacketByteBuf b = new PacketByteBuf(Unpooled.buffer());
				b.writeUuid(packetContext.getPlayer().getUuid());
				watchingPlayers.forEach((player) -> {
					ServerSidePacketRegistry.INSTANCE.sendToPlayer(player, S2C_STOP_SCREEN, b);
				});
				if(MainMod.computers.containsKey(packetContext.getPlayer().getUuid())) {
					MainMod.computers.remove(packetContext.getPlayer().getUuid());
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_CHANGE_HDD, (packetContext, attachedData) -> {
			String newHddName = attachedData.readString(32767);
			
			packetContext.getTaskQueue().execute(() -> {
				for(ItemStack is : packetContext.getPlayer().getItemsHand()) {
					if(is != null) {
						if(is.getItem() instanceof ItemHarddrive) {
							CompoundTag ct = is.getOrCreateTag();
							ct.putString("vhdfile", newHddName);
							ct.putUuid("owner", packetContext.getPlayer().getUuid());
							break;
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_ADD_MOBO, (packetContext, attachedData) -> {
			boolean x64 = attachedData.readBoolean();
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Item lookingFor = null;
				if(x64) {lookingFor = ItemList.ITEM_MOTHERBOARD64;} else {lookingFor = ItemList.ITEM_MOTHERBOARD;}
				if(packetContext.getPlayer().inventory.contains(new ItemStack(lookingFor))) {
					ItemStack inInv = packetContext.getPlayer().inventory.getInvStack(getSlotWithStack(packetContext.getPlayer().inventory, new ItemStack(lookingFor)));
					Entity e = packetContext.getPlayer().world.getEntityById(entityId);
					if(e != null) {
						if (e instanceof EntityPC) {
							EntityPC pc = (EntityPC) e;
							if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
								if(!pc.getMotherboardInstalled()) {
									inInv.decrement(1);
									pc.setMotherboardInstalled(true);
									pc.set64Bit(x64);
								}
							}
						}
					}
				}else {
					packetContext.getPlayer().sendMessage(new TranslatableText("mcvmcomputers.motherboard_not_present").formatted(Formatting.RED));
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_ADD_GPU, (packetContext, attachedData) -> {
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Item lookingFor = ItemList.ITEM_GPU;
				if(packetContext.getPlayer().inventory.contains(new ItemStack(lookingFor))) {
					ItemStack inInv = packetContext.getPlayer().inventory.getInvStack(getSlotWithStack(packetContext.getPlayer().inventory, new ItemStack(lookingFor)));
					Entity e = packetContext.getPlayer().world.getEntityById(entityId);
					if(e != null) {
						if (e instanceof EntityPC) {
							EntityPC pc = (EntityPC) e;
							if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
								if(!pc.getGpuInstalled()) {
									inInv.decrement(1);
									pc.setGpuInstalled(true);
								}
							}
						}
					}
				}else {
					packetContext.getPlayer().sendMessage(new TranslatableText("mcvmcomputers.gpu_not_present").formatted(Formatting.RED));
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_ADD_CPU, (packetContext, attachedData) -> {
			int dividedBy = attachedData.readInt();
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Item lookingFor = null;
				if(dividedBy == 2) {lookingFor = ItemList.ITEM_CPU2;} else if(dividedBy == 4) {lookingFor = ItemList.ITEM_CPU4;} else if(dividedBy == 6) {lookingFor = ItemList.ITEM_CPU6;}
				if(packetContext.getPlayer().inventory.contains(new ItemStack(lookingFor))) {
					ItemStack inInv = packetContext.getPlayer().inventory.getInvStack(getSlotWithStack(packetContext.getPlayer().inventory, new ItemStack(lookingFor)));
					Entity e = packetContext.getPlayer().world.getEntityById(entityId);
					if(e != null) {
						if (e instanceof EntityPC) {
							EntityPC pc = (EntityPC) e;
							if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
								if(pc.getCpuDividedBy() == 0) {
									inInv.decrement(1);
									pc.setCpuDividedBy(dividedBy);
								}
							}
						}
					}
				}else {
					packetContext.getPlayer().sendMessage(new TranslatableText("mcvmcomputers.cpu_not_present").formatted(Formatting.RED));
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_ADD_RAM, (packetContext, attachedData) -> {
			int gb = attachedData.readInt();
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Item lookingFor = null;
				if(gb == 1) {lookingFor = ItemList.ITEM_RAM1G;} else if(gb == 2) {lookingFor = ItemList.ITEM_RAM2G;} else if(gb == 4) {lookingFor = ItemList.ITEM_RAM4G;}
				if(packetContext.getPlayer().inventory.contains(new ItemStack(lookingFor))) {
					ItemStack inInv = packetContext.getPlayer().inventory.getInvStack(getSlotWithStack(packetContext.getPlayer().inventory, new ItemStack(lookingFor)));
					Entity e = packetContext.getPlayer().world.getEntityById(entityId);
					if(e != null) {
						if (e instanceof EntityPC) {
							EntityPC pc = (EntityPC) e;
							if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
								if(pc.getGigsOfRamInSlot0() == 0) {
									inInv.decrement(1);
									pc.setGigsOfRamInSlot0(gb);
								} else if(pc.getGigsOfRamInSlot1() == 0) {
									inInv.decrement(1);
									pc.setGigsOfRamInSlot1(gb);
								}
							}
						}
					}
				}else {
					packetContext.getPlayer().sendMessage(new TranslatableText("mcvmcomputers.cpu_not_present").formatted(Formatting.RED));
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_ADD_HARD_DRIVE, (packetContext, attachedData) -> {
			String vhdname = attachedData.readString(32767);
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				ItemStack lookingFor = ItemHarddrive.createHardDrive(vhdname, packetContext.getPlayer().getUuid().toString());
				if(packetContext.getPlayer().inventory.contains(lookingFor)) {
					ItemStack inInv = packetContext.getPlayer().inventory.getInvStack(getSlotWithStack(packetContext.getPlayer().inventory, lookingFor));
					Entity e = packetContext.getPlayer().world.getEntityById(entityId);
					if(e != null) {
						if (e instanceof EntityPC) {
							EntityPC pc = (EntityPC) e;
							if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
								if(pc.getHardDriveFileName().isEmpty()) {
									inInv.decrement(1);
									pc.setHardDriveFileName(vhdname);
								}
							}
						}
					}
				}else {
					packetContext.getPlayer().sendMessage(new TranslatableText("mcvmcomputers.cpu_not_present").formatted(Formatting.RED));
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_REMOVE_MOBO, (packetContext, attachedData) -> {
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							if(pc.getMotherboardInstalled()) {
								pc.setMotherboardInstalled(false);
								if(pc.get64Bit()) {
									pc.world.spawnEntity(new ItemEntity(pc.world, pc.getX(), pc.getY(), pc.getZ(), new ItemStack(ItemList.ITEM_MOTHERBOARD64)));
								}else {
									pc.world.spawnEntity(new ItemEntity(pc.world, pc.getX(), pc.getY(), pc.getZ(), new ItemStack(ItemList.ITEM_MOTHERBOARD)));
								}
								removeCpu(pc);
								removeGpu(pc);
								removeHdd(pc, packetContext.getPlayer().getUuid().toString());
								removeRam(pc, 0);
								removeRam(pc, 1);
							}
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_REMOVE_GPU, (packetContext, attachedData) -> {
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							removeGpu(pc);
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_REMOVE_HARD_DRIVE, (packetContext, attachedData) -> {
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							removeHdd(pc, packetContext.getPlayer().getUuid().toString());
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_REMOVE_CPU, (packetContext, attachedData) -> {
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							removeCpu(pc);
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_REMOVE_RAM, (packetContext, attachedData) -> {
			int slot = attachedData.readInt();
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							removeRam(pc, slot);
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_ADD_ISO, (packetContext, attachedData) -> {
			String isoName = attachedData.readString(32767);
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							if(pc.getIsoFileName().isEmpty()) {
								pc.setIsoFileName(isoName);
							}
						}
					}
				}
			});
		});
		
		ServerSidePacketRegistry.INSTANCE.register(C2S_REMOVE_ISO, (packetContext, attachedData) -> {
			int entityId = attachedData.readInt();
			
			packetContext.getTaskQueue().execute(() -> {
				Entity e = packetContext.getPlayer().world.getEntityById(entityId);
				if(e != null) {
					if (e instanceof EntityPC) {
						EntityPC pc = (EntityPC) e;
						if(pc.getOwner().equals(packetContext.getPlayer().getUuid().toString())) {
							if(!pc.getIsoFileName().isEmpty()) {
								pc.setIsoFileName("");
							}
						}
					}
				}
			});
		});
	}
	
	private static int getSlotWithStack(PlayerInventory playerInv, ItemStack is) {
		for(int i = 0; i < playerInv.main.size(); ++i) {
	         if (!((ItemStack)playerInv.main.get(i)).isEmpty() && areItemsEqual(is, (ItemStack)playerInv.main.get(i))) {
	            return i;
	         }
	      }

	      return -1;
	}
	
	private static boolean areItemsEqual(ItemStack stack1, ItemStack stack2) {
		return stack1.getItem() == stack2.getItem() && ItemStack.areTagsEqual(stack1, stack2);
	}
}
