package xyz.oribuin.eternaltags.gui.menu;

import dev.rosewood.rosegarden.config.CommentedConfigurationSection;
import dev.rosewood.rosegarden.utils.StringPlaceholders;
import dev.triumphteam.gui.components.GuiAction;
import dev.triumphteam.gui.components.ScrollType;
import dev.triumphteam.gui.guis.BaseGui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import xyz.oribuin.eternaltags.EternalTags;
import xyz.oribuin.eternaltags.gui.MenuItem;
import xyz.oribuin.eternaltags.gui.MenuProvider;
import xyz.oribuin.eternaltags.gui.PluginMenu;
import xyz.oribuin.eternaltags.gui.enums.SortType;
import xyz.oribuin.eternaltags.manager.ConfigurationManager.Setting;
import xyz.oribuin.eternaltags.manager.TagsManager;
import xyz.oribuin.eternaltags.obj.Category;
import xyz.oribuin.eternaltags.util.ItemBuilder;
import xyz.oribuin.eternaltags.util.TagsUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CategoryGUI extends PluginMenu {

    private final TagsManager manager = this.rosePlugin.getManager(TagsManager.class);
    private final Map<Category, GuiItem> categoryIcons = new LinkedHashMap<>(); // Cache the tag items, so we don't have to create them every time.

    public CategoryGUI() {
        super(EternalTags.getInstance());
    }

    @Override
    public void load() {
        super.load();

        this.categoryIcons.clear();
    }

    public void open(@NotNull Player player) {
        // Check if categories are enabled.
        if (!manager.isCategoriesEnabled()) {
            MenuProvider.get(TagsGUI.class).open(player);
            return;
        }

        String menuTitle = this.config.getString("gui-settings.title");
        if (menuTitle == null)
            menuTitle = "Category Menu";

        String finalMenuTitle = menuTitle;

        boolean scrollingGui = this.config.getBoolean("gui-settings.scrolling-gui", false);
        ScrollType scrollingType = this.match(this.config.getString("gui-settings.scrolling-type"));

        PaginatedGui gui = (scrollingGui && scrollingType != null) ? this.createScrollingGui(player, scrollingType) : this.createPagedGUI(player);

        final CommentedConfigurationSection extraItems = this.config.getConfigurationSection("extra-items");
        if (extraItems != null) {
            for (String key : extraItems.getKeys(false)) {
                MenuItem.create(this.config)
                        .path("extra-items." + key)
                        .player(player)
                        .place(gui);
            }
        }

        MenuItem.create(this.config)
                .path("next-page")
                .player(player)
                .action(event -> {
                    gui.next();
                    this.sync(() -> gui.updateTitle(this.formatString(player, finalMenuTitle, this.getPagePlaceholders(gui))));
                })
                .player(player)
                .place(gui);

        MenuItem.create(this.config)
                .path("previous-page")
                .player(player)
                .action(event -> {
                    gui.previous();
                    this.sync(() -> gui.updateTitle(this.formatString(player, finalMenuTitle, this.getPagePlaceholders(gui))));
                })
                .place(gui);

        MenuItem.create(this.config)
                .path("clear-tag")
                .player(player)
                .action(event -> this.clearTag(player))
                .place(gui);

        MenuItem.create(this.config)
                .path("favorite-tags")
                .player(player)
                .action(event -> {
                    Player viewer = (Player) event.getClickedInventory().getViewers().stream().findFirst().orElse(null);
                    if (viewer == null) return;

                    MenuProvider.get(FavouritesGUI.class).open(viewer);
                })
                .place(gui);

        MenuItem.create(this.config)
                .path("search")
                .player(player)
                .action(event -> this.searchTags(player, gui))
                .place(gui);

        MenuItem.create(this.config)
                .path("main-menu")
                .player(player)
                .action(event -> MenuProvider.get(TagsGUI.class).open(player, null))
                .place(gui);

        gui.open(player);

        int dynamicSpeed = this.config.getInt("gui-settings.dynamic-speed", 3);
        if (this.config.getBoolean("gui-settings.dynamic-gui", false) && dynamicSpeed > 0) {
            this.rosePlugin.getServer().getScheduler().runTaskTimerAsynchronously(this.rosePlugin, task -> {
                if (gui.getInventory().getViewers().isEmpty()) {
                    task.cancel();
                    return;
                }

                this.addCategories(gui, player);

                if (this.reloadTitle())
                    this.sync(() -> gui.updateTitle(this.formatString(player, finalMenuTitle, this.getPagePlaceholders(gui))));
            }, 0, dynamicSpeed);

            return;
        }

        Runnable task = () -> {
            this.addCategories(gui, player);

            if (this.reloadTitle())
                this.sync(() -> gui.updateTitle(this.formatString(player, finalMenuTitle, this.getPagePlaceholders(gui))));
        };

        if (this.addPagesAsynchronously()) this.async(task);
        else task.run();

    }

    /**
     * Add the categories to the gui.
     *
     * @param gui    The gui to add the categories to.
     * @param player The player to add the categories for.
     */
    public void addCategories(@NotNull BaseGui gui, @NotNull Player player) {
        if (gui instanceof PaginatedGui paginated)
            paginated.clearPageItems();

        final TagsGUI tagsGUI = MenuProvider.get(TagsGUI.class);
        if (tagsGUI == null) // This should never happen, but just in case.
            return;

        this.getCategories(player).forEach(category -> {

            final GuiAction<InventoryClickEvent> action = event -> {
                Player viewer = (Player) event.getClickedInventory().getViewers().stream().findFirst().orElse(null);
                if (viewer == null) return;

                // Filter out tags that are not in the category.
                if (category.isGlobal()) {
                    tagsGUI.open(viewer);
                    return;
                }

                tagsGUI.open(viewer, tag -> tag.getCategory() != null && tag.getCategory().equalsIgnoreCase(category.getId()));
            };

            if (Setting.CACHE_GUI_CATEGORIES.getBoolean() && this.categoryIcons.containsKey(category)) {
                GuiItem item = this.categoryIcons.get(category);
                item.setAction(action);

                gui.addItem(item);
                return;
            }

            StringPlaceholders.Builder placeholders = StringPlaceholders.builder()
                    .add("category", category.getDisplayName())
                    .add("total", this.manager.getTagsInCategory(category).size());

            if (this.config.getBoolean("gui-settings.only-unlocked-categories"))
                placeholders.add("unlocked", this.manager.getAccessibleTagsInCategory(category, player).size());

            ItemStack item = TagsUtils.deserialize(this.config, player, "categories." + category.getId() + ".display-item", placeholders.build());
            if (item == null) {
                this.rosePlugin.getLogger().info("Failed to load category " + category.getId() + " for the gui, the display item is invalid, Using default value.");

                item = new ItemBuilder(Material.OAK_SIGN)
                        .name(formatString(player, "#00B4DB" + category.getDisplayName()))
                        .build();
            }

            GuiItem guiItem = new GuiItem(item, action);
            gui.addItem(guiItem);

            if (Setting.CACHE_GUI_CATEGORIES.getBoolean())
                this.categoryIcons.put(category, guiItem);
        });

    }

    /**
     * Get a list of categories
     *
     * @param player The player to get the categories for
     * @return A list of categories
     */
    public List<Category> getCategories(@NotNull Player player) {
        final List<Category> categories = new ArrayList<>(this.manager.getCachedCategories().values());
        final SortType sortType = TagsUtils.getEnum(
                SortType.class,
                this.config.getString("gui-settings.sort-type"),
                SortType.ALPHABETICAL
        );

        if (this.config.getBoolean("gui-settings.use-category-permissions", false)) {
            categories.removeIf(category -> {
                if (category.isGlobal() || category.getPermission() == null)
                    return false;

                return !player.hasPermission(category.getPermission());
            });
        }

        if (this.config.getBoolean("gui-settings.only-unlocked-categories", false)) {
            categories.removeIf(category -> !category.isGlobal() && this.manager.getAccessibleTagsInCategory(category, player).isEmpty());
        }

        // TODO: 2023-05-07 Fix this code
//        categories.removeIf(category -> !category.isGlobal() || this.config.getBoolean("categories." + category.getId() + ".hidden"));
//        categories.removeIf(category -> {
//            if (category.isGlobal())
//                return false;
//
//            return this.config.getBoolean("categories." + category.getId() + ".hidden");
//        });

        sortType.sortCategories(categories);

        return categories;
    }

    @Override
    public String getMenuName() {
        return "category-gui";
    }

}
