package com.example.camundademo.dto;

import java.util.List;

public class CommTarget {
    private String type; // USER/DEPT
    private String id;
    private String name;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}