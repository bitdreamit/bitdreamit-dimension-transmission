package com.mirth.connect.model.converters;
import java.util.List;
public class ObjectXMLSerializer {
    private static final ObjectXMLSerializer INSTANCE = new ObjectXMLSerializer();
    public static ObjectXMLSerializer getInstance() { return INSTANCE; }
    public void allowTypes(List<Class<?>> classes, List<String> wildcardPrefixes, List<String> directClasses) {}
}
