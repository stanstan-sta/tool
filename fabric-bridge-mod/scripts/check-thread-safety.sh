#!/bin/bash
# Check for thread safety violations in Java source files

echo "=== Checking for thread safety violations ==="

VIOLATIONS=0

# Check for direct MinecraftClient access outside ClientThread
echo ""
echo "--- Checking for direct MinecraftClient.player access ---"
VIOLATIONS_1=$(find src/main/java -name "*.java" -exec grep -n "client\.player\." {} + 2>/dev/null | grep -v "ClientThread" | grep -v "// SAFE:" | grep -v "/\* SAFE" || true)
if [ -n "$VIOLATIONS_1" ]; then
    echo "POTENTIAL VIOLATIONS:"
    echo "$VIOLATIONS_1"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

echo ""
echo "--- Checking for direct client.world access ---"
VIOLATIONS_2=$(find src/main/java -name "*.java" -exec grep -n "client\.world\." {} + 2>/dev/null | grep -v "ClientThread" | grep -v "// SAFE:" || true)
if [ -n "$VIOLATIONS_2" ]; then
    echo "POTENTIAL VIOLATIONS:"
    echo "$VIOLATIONS_2"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

echo ""
echo "--- Checking for direct interactionManager access ---"
VIOLATIONS_3=$(find src/main/java -name "*.java" -exec grep -n "client\.interactionManager\." {} + 2>/dev/null | grep -v "ClientThread" | grep -v "// SAFE:" || true)
if [ -n "$VIOLATIONS_3" ]; then
    echo "POTENTIAL VIOLATIONS:"
    echo "$VIOLATIONS_3"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

echo ""
echo "--- Checking for player.getInventory() outside ClientThread ---"
VIOLATIONS_4=$(find src/main/java -name "*.java" -exec grep -n "player\.getInventory()" {} + 2>/dev/null | grep -v "ClientThread" | grep -v "// SAFE:" || true)
if [ -n "$VIOLATIONS_4" ]; then
    echo "POTENTIAL VIOLATIONS:"
    echo "$VIOLATIONS_4"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

echo ""
echo "--- Checking for player.isDead() / player.getHealth() outside ClientThread ---"
VIOLATIONS_5=$(find src/main/java -name "*.java" -exec grep -n "player\.isDead()\|player\.getHealth()" {} + 2>/dev/null | grep -v "ClientThread" | grep -v "// SAFE:" || true)
if [ -n "$VIOLATIONS_5" ]; then
    echo "POTENTIAL VIOLATIONS:"
    echo "$VIOLATIONS_5"
    VIOLATIONS=$((VIOLATIONS + 1))
fi

echo ""
if [ $VIOLATIONS -gt 0 ]; then
    echo "=== Found $VIOLATIONS potential violation areas ==="
    echo "Review each line. If it's a real violation, wrap in ClientThread.call()/run()"
    echo "If it's safe (e.g., already on client thread), add // SAFE: comment explaining why"
    exit 1
else
    echo "=== No thread safety violations found ==="
    exit 0
fi
