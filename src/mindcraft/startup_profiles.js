import { readFileSync, realpathSync, statSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const RESERVED_FILES = new Set(['keys.json', 'keys.example.json', 'package.json', 'package-lock.json', 'settings_local.json']);

export function validateProfile(profile) {
    if (!profile || typeof profile !== 'object' || Array.isArray(profile)) {
        throw new Error('Profile must be a JSON object.');
    }
    if (typeof profile.name !== 'string' || !profile.name.trim()) {
        throw new Error('Profile must have a non-empty "name" string.');
    }
}

function allowedRelativePath(root, filePath) {
    const relative = path.relative(root, filePath);
    const parts = relative.split(path.sep);
    return relative && !path.isAbsolute(relative) && !parts.includes('..')
        && !relative.includes(':') && path.extname(relative).toLowerCase() === '.json'
        && (parts.length === 1 ? !RESERVED_FILES.has(relative.toLowerCase()) : parts[0] === 'profiles');
}

// The dashboard supports the profile library and legacy root profiles (miku.json).
// Check both the requested path and its real target so symlinks cannot escape.
export function readStartupProfile(profilePath) {
    if (typeof profilePath !== 'string' || !profilePath.trim()) {
        throw new Error('Startup profile paths must be non-empty strings.');
    }
    const requested = path.resolve(REPO_ROOT, profilePath);
    if (!allowedRelativePath(REPO_ROOT, requested)) {
        throw new Error('Startup profiles must be JSON files in profiles/ or the repository root.');
    }
    const root = realpathSync(REPO_ROOT);
    const filePath = realpathSync(requested);
    if (!allowedRelativePath(root, filePath) || !statSync(filePath).isFile()) {
        throw new Error('Startup profile target is outside the profile library or is not a file.');
    }
    const profile = JSON.parse(readFileSync(filePath, 'utf8'));
    validateProfile(profile);
    if (path.dirname(filePath) === root && !profile.model && !profile.personality) {
        throw new Error('Root startup files must contain a profile model or personality.');
    }
    return { profile, path: `./${path.relative(root, filePath).split(path.sep).join('/')}` };
}
