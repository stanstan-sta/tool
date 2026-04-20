export async function prepareFabricRuntime(settings) {
    const next = { ...settings };
    next.bridge_mode = true;
    const launchMode = next.launch_mode || 'fabric_ui';
    next.fabric_headless = launchMode === 'fabric_headless';
    return {
        settings: next,
        runtime: 'fabric',
    };
}

