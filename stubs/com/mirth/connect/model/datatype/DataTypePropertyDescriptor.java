package com.mirth.connect.model.datatype;
public class DataTypePropertyDescriptor {
    private Object value;
    private String name;
    private String description;
    private PropertyEditorType editorType;
    private Object[] options;
    public DataTypePropertyDescriptor(Object value, String name, String description, PropertyEditorType editorType) {
        this.value = value; this.name = name; this.description = description; this.editorType = editorType;
    }
    public DataTypePropertyDescriptor(Object value, String name, String description, PropertyEditorType editorType, Object[] options) {
        this(value, name, description, editorType); this.options = options;
    }
    public Object getValue() { return value; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public PropertyEditorType getEditorType() { return editorType; }
    public Object[] getOptions() { return options; }
}
