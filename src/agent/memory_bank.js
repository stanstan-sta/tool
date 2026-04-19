import { wiki } from '../utils/MinecraftWiki.js';

export class MemoryBank {
	constructor() {
		this.memory = {};
		this.chests = {}; // { "x,y,z": { coords: {x,y,z}, categories: {}, items: {}, simplifications: [], last_updated: timestamp } }
	}

	rememberPlace(name, x, y, z) {
		this.memory[name] = [x, y, z];
	}

	recallPlace(name) {
		return this.memory[name];
	}

	getJson() {
		return { memory: this.memory, chests: this.chests };
	}

	loadJson(json) {
		if (json && typeof json === 'object' && ('memory' in json || 'chests' in json)) {
			this.memory = json.memory || {};
			this.chests = json.chests || {};
		} else {
			// Legacy format: plain memory object
			this.memory = json || {};
			this.chests = {};
		}
	}

	getKeys() {
		return Object.keys(this.memory).join(', ');
	}

	// ── Chest memory ────────────────────────────────────────────────

	/**
	 * Remember chest contents at given coordinates.
	 * Uses the wiki to categorize and simplify the contents.
	 * @param {number} x
	 * @param {number} y
	 * @param {number} z
	 * @param {Object} items - { item_name: count }
	 */
	rememberChest(x, y, z, items) {
		const key = `${Math.round(x)},${Math.round(y)},${Math.round(z)}`;
		const categories = wiki.categorizeItems(Object.keys(items));
		const simplifications = wiki.simplifyChestContents(items);
		this.chests[key] = {
			coords: { x: Math.round(x), y: Math.round(y), z: Math.round(z) },
			items,
			categories,
			simplifications,
			last_updated: Date.now()
		};
	}

	/**
	 * Recall stored info about a chest at given coordinates.
	 * @param {number} x
	 * @param {number} y
	 * @param {number} z
	 * @returns {Object|null}
	 */
	recallChest(x, y, z) {
		const key = `${Math.round(x)},${Math.round(y)},${Math.round(z)}`;
		return this.chests[key] || null;
	}

	/**
	 * Get a summary string of all remembered chests for use in prompts.
	 * @returns {string}
	 */
	getChestSummary() {
		const entries = Object.values(this.chests);
		if (entries.length === 0) return 'No chests remembered.';
		return entries.map(c => {
			const pos = `(${c.coords.x}, ${c.coords.y}, ${c.coords.z})`;
			const catStr = Object.entries(c.categories)
				.map(([cat, items]) => `${cat}: ${items.join(', ')}`)
				.join('; ');
			const simpl = c.simplifications.length > 0 ? ' | ' + c.simplifications.join('; ') : '';
			return `Chest at ${pos} [${catStr}${simpl}]`;
		}).join('\n');
	}

	/**
	 * Find chests that contain items of a given category (e.g. "fuel").
	 * @param {string} category
	 * @returns {Array<Object>}
	 */
	findChestsByCategory(category) {
		return Object.values(this.chests).filter(c => c.categories[category] && c.categories[category].length > 0);
	}

	/**
	 * Handle a mismatch: the agent expected items in a chest but they were absent.
	 * Updates stored data and returns a message noting what needs to be obtained.
	 * @param {number} x
	 * @param {number} y
	 * @param {number} z
	 * @param {Object} actualItems - actual chest contents (may be empty or different)
	 * @returns {string} mismatch message
	 */
	handleChestMismatch(x, y, z, actualItems) {
		const stored = this.recallChest(x, y, z);
		if (!stored) return '';

		const missing = Object.keys(stored.items).filter(item => !(item in actualItems) || actualItems[item] === 0);
		// Update stored data to reflect reality
		this.rememberChest(x, y, z, actualItems);

		if (missing.length === 0) return '';
		const suggestions = missing.map(item => {
			const recipe = wiki.getRecipe(item);
			if (recipe) return `${item} (can craft: ${wiki.formatRecipeSummary(item)})`;
			return `${item} (need to obtain)`;
		});
		return `Chest at (${Math.round(x)},${Math.round(y)},${Math.round(z)}) is missing expected items: ${suggestions.join(', ')}. Need to obtain these resources.`;
	}

	/**
	 * Remove a chest entry from memory.
	 * @param {number} x
	 * @param {number} y
	 * @param {number} z
	 */
	forgetChest(x, y, z) {
		const key = `${Math.round(x)},${Math.round(y)},${Math.round(z)}`;
		delete this.chests[key];
	}
}
