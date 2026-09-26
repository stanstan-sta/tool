package com.mindcraft.protocol;

public class EventMessage {
    public int v = 1;
    public String type = "event";
    public String kind;
    public String player, dim, block, cause, from, to;
    public Integer x, y, z;
    public Long ts;
}
