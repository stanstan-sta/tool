import { readFileSync } from 'fs';
import { fileURLToPath } from 'url';
import path from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

let wikiData = null;

function loadWiki() {
    if (wikiData) return wikiData;
    try {
        const fp = path.join(__dirname, 'minecraft_wiki.json');
        wikiData = JSON.parse(readFileSync(fp, 'utf8'));
    } catch (err) {
        console.error('MinecraftWiki: failed to load minecraft_wiki.json', err);
        wikiData = {};
    }
    return wikiData;
}

export class MinecraftWiki {
    constructor() {
        this.data = loadWiki();
    }

    /**
     * Returns the crafting or smelting recipe for an item, or null if unknown.
     * @param {string} item - item name (e.g. "cooked_beef", "iron_ingot")
     * @returns {Object|null}
     */
    getRecipe(item) {
        const item_lower = item.toLowerCase().trim();
        const crafting = this.data.recipes?.crafting?.[item_lower];
        if (crafting) return { recipeSection: 'crafting', ...crafting };
        const smelting = this.data.recipes?.smelting?.[item_lower];
        if (smelting) return { recipeSection: 'smelting', ...smelting };
        const brewing = this.data.recipes?.brewing?.[item_lower];
        if (brewing) return { recipeSection: 'brewing', ...brewing };
        return null;
    }

    /**
     * Returns all items in a given category (e.g. "fuel", "raw_food"), or null.
     * @param {string} categoryName
     * @returns {Object|null}
     */
    getCategory(categoryName) {
        const key = categoryName.toLowerCase().trim();
        return this.data.categories?.[key] || null;
    }

    /**
     * Returns which category (if any) an item belongs to.
     * @param {string} item
     * @returns {string|null} category name or null
     */
    getItemCategory(item) {
        const item_lower = item.toLowerCase().trim();
        const categories = this.data.categories || {};
        for (const [catName, catData] of Object.entries(categories)) {
            const items = catData.items;
            if (!items) continue;
            if (Array.isArray(items)) {
                if (items.includes(item_lower)) return catName;
            } else if (typeof items === 'object') {
                if (item_lower in items) return catName;
            }
        }
        return null;
    }

    /**
     * Returns biome info by name, or null.
     * @param {string} biome
     * @returns {Object|null}
     */
    getBiome(biome) {
        return this.data.biomes?.[biome.toLowerCase().trim()] || null;
    }

    /**
     * Returns game mechanic info by name, or null.
     * @param {string} mechanic
     * @returns {Object|null}
     */
    getMechanic(mechanic) {
        return this.data.mechanics?.[mechanic.toLowerCase().trim()] || null;
    }

    /**
     * Searches the wiki across all sections for the query string.
     * Returns an array of result objects with { type, name, data }.
     * @param {string} query
     * @returns {Array<Object>}
     */
    search(query) {
        const q = query.toLowerCase().trim();
        const results = [];

        // Check recipes
        for (const section of ['crafting', 'smelting', 'brewing']) {
            const section_data = this.data.recipes?.[section] || {};
            for (const [name, data] of Object.entries(section_data)) {
                if (name.includes(q)) {
                    results.push({ type: `recipe_${section}`, name, data });
                }
            }
        }

        // Check categories
        const categories = this.data.categories || {};
        for (const [catName, catData] of Object.entries(categories)) {
            if (catName.includes(q)) {
                results.push({ type: 'category', name: catName, data: catData });
                continue;
            }
            const items = catData.items;
            if (!items) continue;
            const itemKeys = Array.isArray(items) ? items : Object.keys(items);
            for (const itemName of itemKeys) {
                if (itemName.includes(q)) {
                    results.push({ type: 'category_item', name: itemName, category: catName, data: Array.isArray(items) ? {} : items[itemName] });
                }
            }
        }

        // Check biomes
        const biomes = this.data.biomes || {};
        for (const [name, data] of Object.entries(biomes)) {
            if (name.includes(q) || (data.resources && data.resources.some(r => r.includes(q)))) {
                results.push({ type: 'biome', name, data });
            }
        }

        // Check mechanics
        const mechanics = this.data.mechanics || {};
        for (const [name, data] of Object.entries(mechanics)) {
            if (name.includes(q) || JSON.stringify(data).toLowerCase().includes(q)) {
                results.push({ type: 'mechanic', name, data });
            }
        }

        // Check mob drops
        const mobs = this.data.categories?.mob_drops?.mobs || {};
        for (const [name, data] of Object.entries(mobs)) {
            if (name.includes(q) || (data.drops && data.drops.some(d => d.includes(q)))) {
                results.push({ type: 'mob', name, data });
            }
        }

        return results;
    }

    /**
     * Formats a recipe or search result as a short human-readable string for chat.
     * @param {string} item
     * @returns {string}
     */
    formatRecipeSummary(item) {
        const recipe = this.getRecipe(item);
        if (!recipe) return `${item}: unknown recipe - query model`;
        if (recipe.recipeSection === 'crafting') {
            if (recipe.notes && !recipe.ingredients) {
                return `${item}: ${recipe.notes}`;
            }
            const ings = Object.entries(recipe.ingredients || {})
                .map(([k, v]) => `${v}x ${k}`)
                .join(', ');
            const method = recipe.method || 'crafting table';
            return `${item}: ${ings} -> ${recipe.output || 1}x (${method})${recipe.notes ? '. ' + recipe.notes : ''}`;
        }
        if (recipe.recipeSection === 'smelting') {
            return `${item}: ${recipe.input} + fuel -> ${recipe.output || 1}x (furnace)${recipe.notes ? '. ' + recipe.notes : ''}`;
        }
        if (recipe.recipeSection === 'brewing') {
            return `${item}: ${recipe.base} + ${recipe.ingredient} (brewing stand)${recipe.effect ? '. Effect: ' + recipe.effect : ''}`;
        }
        return `${item}: ${JSON.stringify(recipe)}`;
    }

    /**
     * Validates whether provided ingredients can produce the target item.
     * Returns { valid: bool, missing: [], output: string|null }
     * @param {string[]} ingredients - list of item names the agent has
     * @param {string} targetItem
     * @returns {Object}
     */
    validateRecipe(ingredients, targetItem) {
        const recipe = this.getRecipe(targetItem);
        if (!recipe) return { valid: false, missing: [], output: null, reason: 'unknown recipe' };

        const ingSet = new Set(ingredients.map(i => i.toLowerCase()));
        const required = recipe.ingredients || {};
        const missing = [];

        if (recipe.recipeSection === 'smelting') {
            // Check input and fuel
            const input = recipe.input?.split('+').map(s => s.trim()) || [];
            for (const inp of input) {
                if (!ingSet.has(inp) && inp !== 'any_fuel' && inp !== 'any_log') {
                    // check generic categories
                    if (inp === 'any_fuel') {
                        if (!this._hasAnyFuel(ingSet)) missing.push('fuel');
                    } else {
                        missing.push(inp);
                    }
                }
            }
            if (!this._hasAnyFuel(ingSet)) missing.push('fuel');
        } else {
            for (const [ing] of Object.entries(required)) {
                if (!ingSet.has(ing)) missing.push(ing);
            }
        }

        return { valid: missing.length === 0, missing, output: targetItem };
    }

    /**
     * Checks if a set of item names contains any recognized fuel.
     * @param {Set<string>} itemSet
     * @returns {boolean}
     */
    _hasAnyFuel(itemSet) {
        const fuelItems = Object.keys(this.data.categories?.fuel?.items || {});
        return fuelItems.some(f => itemSet.has(f));
    }

    /**
     * Simplifies a chest contents object into human-readable action summaries.
     * Input: { item_name: count, ... }
     * Output: string[]
     * @param {Object} contents - { item_name: count }
     * @returns {string[]}
     */
    simplifyChestContents(contents) {
        const summaries = [];
        const itemNames = Object.keys(contents);
        const itemSet = new Set(itemNames.map(i => i.toLowerCase()));

        const fuelItems = itemNames.filter(i => this.getItemCategory(i) === 'fuel');
        const rawFoodItems = itemNames.filter(i => this.getItemCategory(i) === 'raw_food');

        if (rawFoodItems.length > 0 && fuelItems.length > 0) {
            const foodList = rawFoodItems.map(f => {
                const recipe = this.data.categories?.raw_food?.items?.[f];
                return recipe ? `${contents[f]}x ${f} -> ${recipe.cooked}` : `${contents[f]}x ${f}`;
            }).join(', ');
            summaries.push(`Can cook: ${foodList} (fuel: ${fuelItems.join(', ')})`);
        }

        const ores = itemNames.filter(i => {
            const cat = this.getItemCategory(i);
            return ['raw_iron', 'raw_gold', 'raw_copper'].includes(i.toLowerCase());
        });
        if (ores.length > 0 && fuelItems.length > 0) {
            summaries.push(`Can smelt: ${ores.map(o => `${contents[o]}x ${o}`).join(', ')} -> ingots`);
        }

        return summaries;
    }

    /**
     * Categorizes a list of items using wiki categories.
     * Returns { category_name: [items] }
     * @param {string[]} items
     * @returns {Object}
     */
    categorizeItems(items) {
        const result = {};
        for (const item of items) {
            const cat = this.getItemCategory(item) || 'misc';
            if (!result[cat]) result[cat] = [];
            result[cat].push(item);
        }
        return result;
    }
}

// Singleton instance for convenience
export const wiki = new MinecraftWiki();
