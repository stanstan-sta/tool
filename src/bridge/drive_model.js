/**
 * Drive model for ambient bot behavior.
 * Four drives decay over time; satisfying a drive resets it.
 * Drives are converted to textual hints for the LLM — never raw numbers.
 */
export class DriveModel {
    constructor() {
        this.drives = {
            social:    { level: 0, decay: 0.0006 },  // rises ~1/min idle
            curiosity: { level: 0, decay: 0.0004 },
            rest:      { level: 0, decay: 0.0002 },
            safety:    { level: 0, decay: 0.0010 },
        };
        this.lastTickMs = Date.now();
    }

    tick(dtMs) {
        for (const key of Object.keys(this.drives)) {
            this.drives[key].level = Math.min(1.0, this.drives[key].level + this.drives[key].decay * dtMs);
        }
        this.lastTickMs = Date.now();
    }

    satisfy(driveName) {
        if (this.drives[driveName]) {
            this.drives[driveName].level = 0;
        }
    }

    bump(driveName, amount = 0.3) {
        if (this.drives[driveName]) {
            this.drives[driveName].level = Math.min(1.0, this.drives[driveName].level + amount);
        }
    }

    getActiveHints() {
        const hints = [];
        if (this.drives.social.level > 0.6) {
            hints.push("You've been quiet for a while.");
        }
        if (this.drives.curiosity.level > 0.5) {
            hints.push("You feel curious about your surroundings.");
        }
        if (this.drives.rest.level > 0.7) {
            hints.push("You should probably rest soon.");
        }
        if (this.drives.safety.level > 0.5) {
            hints.push("Something feels unsafe.");
        }
        return hints;
    }

    getSummary() {
        return {
            social: Math.round(this.drives.social.level * 100) / 100,
            curiosity: Math.round(this.drives.curiosity.level * 100) / 100,
            rest: Math.round(this.drives.rest.level * 100) / 100,
            safety: Math.round(this.drives.safety.level * 100) / 100,
        };
    }
}
