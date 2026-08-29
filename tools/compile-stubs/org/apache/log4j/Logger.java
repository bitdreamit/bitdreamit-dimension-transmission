package org.apache.log4j;
public class Logger {
    public static Logger getLogger(Class<?> c) { return new Logger(); }
    public static Logger getLogger(String s) { return new Logger(); }
    public void debug(Object m) { }
    public void debug(Object m, Throwable t) { }
    public void info(Object m) { }
    public void info(Object m, Throwable t) { }
    public void warn(Object m) { }
    public void warn(Object m, Throwable t) { }
    public void error(Object m) { }
    public void error(Object m, Throwable t) { }
}
