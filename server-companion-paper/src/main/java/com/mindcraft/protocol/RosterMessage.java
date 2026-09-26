package com.mindcraft.protocol;

import java.util.List;

public class RosterMessage {
    public int v = 1;
    public String type = "roster";
    public List<PlayerInfo> players;
}
