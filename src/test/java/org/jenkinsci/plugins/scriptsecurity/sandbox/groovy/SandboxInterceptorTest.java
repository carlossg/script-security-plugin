/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.scriptsecurity.sandbox.groovy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import groovy.json.JsonBuilder;
import groovy.json.JsonDelegate;
import groovy.lang.GString;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.GroovyShell;
import groovy.lang.MetaMethod;
import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;
import hudson.Functions;
import hudson.util.IOUtils;

import java.lang.reflect.Method;
import java.net.URL;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.commons.lang.StringUtils;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.runtime.GStringImpl;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AbstractWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AnnotatedWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.BlanketWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.GenericWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.junit.Ignore;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

public class SandboxInterceptorTest {

    @Test public void genericWhitelist() throws Exception {
        assertEvaluate(new GenericWhitelist(), 3, "'foo bar baz'.split(' ').length");
        assertEvaluate(new GenericWhitelist(), false, "def x = null; x != null");
    }

    /** Checks that {@link GString} is handled sanely. */
    @Test public void testGString() throws Exception {
        String clazz = Clazz.class.getName();
        String script = "def x = 1; new " + clazz + "().method(\"foo${x}\")";
        String expected = "-foo1";
        assertEvaluate(new AnnotatedWhitelist(), expected, script);
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " method java.lang.String"), expected, script);
    }

    /** Checks that methods specifically expecting {@link GString} also work. */
    @Test public void testGString2() throws Exception {
        String clazz = Clazz.class.getName();
        String script = "def x = 1; def c = new " + clazz + "(); c.quote(\"-${c.specialize(x)}-${x}-\")";
        String expected = "-1-'1'-";
        assertEvaluate(new AnnotatedWhitelist(), expected, script);
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " specialize java.lang.Object", "method " + clazz + " quote java.lang.Object"), expected, script);
    }

    @Issue("JENKINS-29541")
    @Test public void substringGString() throws Exception {
        assertEvaluate(new GenericWhitelist(), "hell", "'hello world'.substring(0, 4)");
        assertEvaluate(new GenericWhitelist(), "hell", "def place = 'world'; \"hello ${place}\".substring(0, 4)");
    }

    /**
     * Tests the proper interception of builder-like method.
     */
    @Test public void invokeMethod() throws Exception {
        String script = "def builder = new groovy.json.JsonBuilder(); builder.point { x 5; y 3; }; builder.toString()";
        String expected = "{\"point\":{\"x\":5,\"y\":3}}";
        assertEvaluate(new BlanketWhitelist(), expected, script);
        // this whitelisting strategy isn't ideal
        // see https://issues.jenkins-ci.org/browse/JENKINS-24982
        assertEvaluate(new ProxyWhitelist(
            new AbstractWhitelist() {
                @Override
                public boolean permitsMethod(Method method, Object receiver, Object[] args) {
                    if (method.getName().equals("invokeMethod") && receiver instanceof JsonBuilder)
                        return true;
                    if (method.getName().equals("invokeMethod") && receiver instanceof JsonDelegate)
                        return true;
                    if (method.getName().equals("toString") && receiver instanceof JsonBuilder)
                        return true;
                    return false;
                }
            },
            new StaticWhitelist(
                "new groovy.json.JsonBuilder"
//                "method groovy.json.JsonBuilder toString",
//                "method groovy.json.JsonBuilder invokeMethod java.lang.String java.lang.Object"
        )), expected, script);
        try {
            assertEvaluate(new ProxyWhitelist(), "should be rejected", "class Real {}; def real = new Real(); real.nonexistent(42)");
        } catch (RejectedAccessException x) {
            String message = x.getMessage();
            assertEquals(message, "method groovy.lang.GroovyObject invokeMethod java.lang.String java.lang.Object", x.getSignature());
            assertTrue(message, message.contains("Real nonexistent java.lang.Integer"));
        }
    }

    @Ignore("TODO there are various unhandled cases, such as Closure → SAM, or numeric conversions, or number → String, or boxing/unboxing.")
    @Test public void testNumbers() throws Exception {
        String clazz = Clazz.class.getName();
        String script = "int x = 1; " + clazz + ".incr(x)";
        Long expected = 2L;
        // works but is undesirable: assertEvaluate(new StaticWhitelist("staticMethod " + clazz + " incr java.lang.Integer")), expected, script);
        assertEvaluate(new AnnotatedWhitelist(), expected, script);
        // wrapper types must be declared for primitives:
        assertEvaluate(new StaticWhitelist("staticMethod " + clazz + " incr java.lang.Long"), expected, script);
    }

    @Test public void staticFields() throws Exception {
        String clazz = Clazz.class.getName();
        assertEvaluate(new StaticWhitelist("staticField " + clazz + " flag"), true, clazz + ".flag=true");
        assertTrue(Clazz.flag);
    }

    @Test public void propertiesAndGettersAndSetters() throws Exception {
        String clazz = Clazz.class.getName();
        assertEvaluate(new StaticWhitelist("new " + clazz, "field " + clazz + " prop"), "default", "new " + clazz + "().prop");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp"), "default", "new " + clazz + "().prop");
        assertEvaluate(new StaticWhitelist("new " + clazz, "field " + clazz + " prop", "method " + clazz + " getProp"), "default", "new " + clazz + "().prop");
        assertRejected(new StaticWhitelist("new " + clazz), "method " + clazz + " getProp", "new " + clazz + "().prop");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp", "field " + clazz + " prop"), "edited", "def c = new " + clazz + "(); c.prop = 'edited'; c.getProp()");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp", "method " + clazz + " setProp java.lang.String"), "edited", "def c = new " + clazz + "(); c.prop = 'edited'; c.getProp()");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp", "field " + clazz + " prop", "method " + clazz + " setProp java.lang.String"), "edited", "def c = new " + clazz + "(); c.prop = 'edited'; c.getProp()");
        assertRejected(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp"), "method " + clazz + " setProp java.lang.String", "def c = new " + clazz + "(); c.prop = 'edited'; c.getProp()");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp2"), "default", "new " + clazz + "().prop2");
        assertRejected(new StaticWhitelist("new " + clazz), "method " + clazz + " getProp2", "new " + clazz + "().prop2");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp2", "method " + clazz + " setProp2 java.lang.String"), "edited", "def c = new " + clazz + "(); c.prop2 = 'edited'; c.getProp2()");
        assertRejected(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp2"), "method " + clazz + " setProp2 java.lang.String", "def c = new " + clazz + "(); c.prop2 = 'edited'; c.getProp2()");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " isProp3"), false, "new " + clazz + "().prop3");
        assertRejected(new StaticWhitelist("new " + clazz), "method " + clazz + " isProp3", "new " + clazz + "().prop3");
        assertEvaluate(new StaticWhitelist("staticMethod " + clazz + " isProp4"), true, clazz + ".prop4");
        assertRejected(new StaticWhitelist(), "staticMethod " + clazz + " isProp4", clazz + ".prop4");
        try {
            assertEvaluate(new StaticWhitelist("new " + clazz), "should be rejected", "new " + clazz + "().nonexistent");
        } catch (RejectedAccessException x) {
            assertEquals(null, x.getSignature());
            assertEquals("unclassified field " + clazz + " nonexistent", x.getMessage());
        }
        try {
            assertEvaluate(new StaticWhitelist("new " + clazz), "should be rejected", "new " + clazz + "().nonexistent = 'edited'");
        } catch (RejectedAccessException x) {
            assertEquals(null, x.getSignature());
            assertEquals("unclassified field " + clazz + " nonexistent", x.getMessage());
        }
        assertRejected(new StaticWhitelist("new " + clazz), "method " + clazz + " getProp5", "new " + clazz + "().prop5");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp5"), "DEFAULT", "new " + clazz + "().prop5");
        assertRejected(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp5"), "method " + clazz + " setProp5 java.lang.String", "def c = new " + clazz + "(); c.prop5 = 'EDITED'; c.prop5");
        assertEvaluate(new StaticWhitelist("new " + clazz, "method " + clazz + " getProp5", "method " + clazz + " setProp5 java.lang.String", "method " + clazz + " rawProp5"), "EDITEDedited", "def c = new " + clazz + "(); c.prop5 = 'EDITED'; c.prop5 + c.rawProp5()");
    }

    @Test public void syntheticMethods() throws Exception {
        assertEvaluate(new GenericWhitelist(), 4, "2 + 2");
        assertEvaluate(new GenericWhitelist(), "17", "'' + 17");
    }

    public static final class Clazz {
        static boolean flag;
        @Whitelisted public Clazz() {}
        @Whitelisted public String method(String x) {return "-" + x;}
        @Whitelisted Special specialize(Object o) {
            return new Special(o);
        }
        @Whitelisted String quote(Object o) {
            if (o instanceof GString) {
                GString gs = (GString) o;
                Object[] values = gs.getValues();
                for (int i = 0; i < values.length; i++) {
                    if (values[i] instanceof Special) {
                        values[i] = ((Special) values[i]).o;
                    } else {
                        values[i] = quoteSingle(values[i]);
                    }
                }
                return new GStringImpl(values, gs.getStrings()).toString();
            } else {
                return quoteSingle(o);
            }
        }
        private String quoteSingle(Object o) {
            return "'" + String.valueOf(o) + "'";
        }
        @Whitelisted static long incr(long x) {
            return x + 1;
        }
        private String prop = "default";
        public String getProp() {
            return prop;
        }
        public void setProp(String prop) {
            this.prop = prop;
        }
        private String _prop2 = "default";
        public String getProp2() {
            return _prop2;
        }
        public void setProp2(String prop2) {
            this._prop2 = prop2;
        }
        private boolean _prop3;
        public boolean isProp3() {
            return _prop3;
        }
        public void setProp3(boolean prop3) {
            this._prop3 = prop3;
        }
        public static boolean isProp4() {
            return true;
        }
        private String prop5 = "default";
        public String getProp5() {
            return prop5.toUpperCase(Locale.ENGLISH);
        }
        public void setProp5(String value) {
            prop5 = value.toLowerCase(Locale.ENGLISH);
        }
        public String rawProp5() {
            return prop5;
        }
    }

    @Test public void dynamicProperties() throws Exception {
        String dynamic = Dynamic.class.getName();
        String ctor = "new " + dynamic;
        String getProperty = "method groovy.lang.GroovyObject getProperty java.lang.String";
        String setProperty = "method groovy.lang.GroovyObject setProperty java.lang.String java.lang.Object";
        String script = "def d = new " + dynamic + "(); d.prop = 'val'; d.prop";
        assertEvaluate(new StaticWhitelist(ctor, getProperty, setProperty), "val", script);
        assertRejected(new StaticWhitelist(ctor, setProperty), getProperty, script);
        assertRejected(new StaticWhitelist(ctor), setProperty, script);
    }

    public static final class Dynamic extends GroovyObjectSupport {
        private final Map<String,Object> values = new HashMap<String,Object>();
        @Override public Object getProperty(String n) {
            return values.get(n);
        }
        @Override public void setProperty(String n, Object v) {
            values.put(n, v);
        }
    }

    @Test public void mapProperties() throws Exception {
        assertEvaluate(new GenericWhitelist(), 42, "def m = [:]; m.answer = 42; m.answer");
    }

    public static final class Special {
        final Object o;
        Special(Object o) {
            this.o = o;
        }
    }

    @Issue({"JENKINS-25119", "JENKINS-27725"})
    @Test public void defaultGroovyMethods() throws Exception {
        assertRejected(new ProxyWhitelist(), "staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods toInteger java.lang.String", "'123'.toInteger();");
        assertEvaluate(new GenericWhitelist(), 123, "'123'.toInteger();");
        assertEvaluate(new GenericWhitelist(), Arrays.asList(1, 4, 9), "([1, 2, 3] as int[]).collect({x -> x * x})");
        /* TODO JENKINS-33468 No such property: it for class: groovy.lang.Binding:
        assertEvaluate(new GenericWhitelist(), Arrays.asList(1, 4, 9), "([1, 2, 3] as int[]).collect({it * it})");
        */
        // cover others from DgmConverter:
        assertEvaluate(new GenericWhitelist(), "1970", "new Date(0).format('yyyy', TimeZone.getTimeZone('GMT'))");
        assertEvaluate(new GenericWhitelist(), /* actual value sensitive to local TZ */ DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(0)), "new Date(0).dateTimeString");
        // cover get* and is* methods:
        assertEvaluate(new GenericWhitelist(), 5, "'hello'.chars.length");
        assertEvaluate(new GenericWhitelist(), true, "'42'.number");
        // TODO should also cover set* methods, though these seem rare
        // TODO check DefaultGroovyStaticMethods also (though there are few useful & safe calls there)
    }

    @Test public void whitelistedIrrelevantInsideScript() throws Exception {
        String clazz = Unsafe.class.getName();
        String wl = Whitelisted.class.getName();
        // @Whitelisted does not grant us access to anything new:
        assertEvaluate(new AnnotatedWhitelist(), "ok", " C.m(); class C {@" + wl + " static String m() {return " + clazz + ".ok();}}");
        assertRejected(new AnnotatedWhitelist(), "staticMethod " + clazz + " explode", "C.m(); class C {@" + wl + " static void m() {" + clazz + ".explode();}}");
        // but do not need @Whitelisted on ourselves:
        assertEvaluate(new AnnotatedWhitelist(), "ok", "C.m(); class C {static String m() {return " + clazz + ".ok();}}");
        assertRejected(new AnnotatedWhitelist(), "staticMethod " + clazz + " explode", "C.m(); class C {static void m() {" + clazz + ".explode();}}");
    }

    @Test public void defSyntax() throws Exception {
        String clazz = Unsafe.class.getName();
        Whitelist w = new ProxyWhitelist(new AnnotatedWhitelist(), /* for some reason def syntax triggers this */new StaticWhitelist("method java.util.Collection toArray"));
        assertEvaluate(w, "ok", "m(); def m() {" + clazz + ".ok()}");
        assertEvaluate(w, "ok", "m(); def static m() {" + clazz + ".ok()}");
        assertRejected(w, "staticMethod " + clazz + " explode", "m(); def m() {" + clazz + ".explode()}");
    }

    public static final class Unsafe {
        @Whitelisted public static String ok() {return "ok";}
        public static void explode() {}
        private Unsafe() {}
    }

    /** Expect errors from {@link org.codehaus.groovy.runtime.NullObject}. */
    @Issue("kohsuke/groovy-sandbox #15")
    @Test public void nullPointerException() throws Exception {
        try {
            assertEvaluate(new ProxyWhitelist(), "should be rejected", "def x = null; x.member");
        } catch (NullPointerException x) {
            assertEquals(Functions.printThrowable(x), "Cannot get property 'member' on null object", x.getMessage());
        }
        try {
            assertEvaluate(new ProxyWhitelist(), "should be rejected", "def x = null; x.member = 42");
        } catch (NullPointerException x) {
            assertEquals(Functions.printThrowable(x), "Cannot set property 'member' on null object", x.getMessage());
        }
        try {
            assertEvaluate(new ProxyWhitelist(), "should be rejected", "def x = null; x.member()");
        } catch (NullPointerException x) {
            assertEquals(Functions.printThrowable(x), "Cannot invoke method member() on null object", x.getMessage());
        }
    }

    /**
     * Tests the method invocation / property access through closures.
     *
     * <p>
     * Groovy closures act as a proxy when it comes to property/method access. Based on the configuration, it can
     * access those from some combination of owner/delegate. As this is an important building block for custom DSL,
     * script-security understands this logic and checks access at the actual target of the proxy, so that Closures
     * can be used safely.
     */
    @Test public void closureDelegate() throws Exception {
        ProxyWhitelist rules = new ProxyWhitelist(new GenericWhitelist(), new StaticWhitelist("new java.lang.Exception java.lang.String"));
        assertRejected(rules,
                "method java.lang.Throwable getMessage",
                "{-> delegate = new Exception('oops'); message}()"
        );
        assertRejected(
                rules,
                "method java.lang.Throwable printStackTrace",
                "{-> delegate = new Exception('oops'); printStackTrace()}()"
        );

        rules = new ProxyWhitelist(new GenericWhitelist(), new StaticWhitelist("new java.awt.Point"));
        { // method access
            assertEvaluate(rules, 3,
                    StringUtils.join(Arrays.asList(
                            "class Dummy { def getX() { return 3; } }",
                            "def c = { -> getX() };",
                            "c.resolveStrategy = Closure.DELEGATE_ONLY;",
                            "c.delegate = new Dummy();",
                            "return c();"
                    ), "\n"));
            assertRejected(rules, "method java.awt.geom.Point2D getX",
                    StringUtils.join(Arrays.asList(
                            "def c = { -> getX() };",
                            "c.resolveStrategy = Closure.DELEGATE_ONLY;",
                            "c.delegate = new java.awt.Point();",
                            "return c();"
                    ), "\n"));
        }
        {// property access
            assertEvaluate(rules, 3,
                    StringUtils.join(Arrays.asList(
                            "class Dummy { def getX() { return 3; } }",
                            "def c = { -> x };",
                            "c.resolveStrategy = Closure.DELEGATE_ONLY;",
                            "c.delegate = new Dummy();",
                            "return c();"
                    ), "\n"));
            assertRejected(rules, "method java.awt.geom.Point2D getX",
                    StringUtils.join(Arrays.asList(
                            "def c = { -> x };",
                            "c.resolveStrategy = Closure.DELEGATE_ONLY;",
                            "c.delegate = new java.awt.Point();",
                            "return c();"
                    ), "\n"));
        }
    }

    @Issue("JENKINS-28277")
    @Test public void curry() throws Exception {
        assertEvaluate(new GenericWhitelist(), 'h', "def charAt = {idx, str -> str.charAt(idx)}; def firstChar = charAt.curry(0); firstChar 'hello'");
        assertEvaluate(new GenericWhitelist(), 'h', "def charOfHello = 'hello'.&charAt; def firstCharOfHello = charOfHello.curry(0); firstCharOfHello()");
        assertEvaluate(new GenericWhitelist(), 'h', "def charAt = {str, idx -> str.charAt(idx)}; def firstChar = charAt.ncurry(1, 0); firstChar 'hello'");
    }

    @Test public void templates() throws Exception {
        final GroovyShell shell = new GroovyShell(GroovySandbox.createSecureCompilerConfiguration());
        final Template t = new SimpleTemplateEngine(shell).createTemplate("goodbye <%= aspect.toLowerCase() %> world");
        assertEquals("goodbye cruel world", GroovySandbox.runInSandbox(new Callable<String>() {
            @Override public String call() throws Exception {
                return t.make(new HashMap<String,Object>(Collections.singletonMap("aspect", "CRUEL"))).toString();
            }
        }, new ProxyWhitelist(new StaticWhitelist("method java.lang.String toLowerCase"), new GenericWhitelist())));
    }

    @Test public void selfProperties() throws Exception {
        assertEvaluate(new ProxyWhitelist(), true, "BOOL=true; BOOL");
    }

    @Test public void missingPropertyException() throws Exception {
        try {
            assertEvaluate(new ProxyWhitelist(), "should fail", "GOOP");
        } catch (MissingPropertyException x) {
            assertEquals("GOOP", x.getProperty());
        }
    }

    @Test public void specialScript() throws Exception {
        CompilerConfiguration cc = GroovySandbox.createSecureCompilerConfiguration();
        cc.setScriptBaseClass(SpecialScript.class.getName());
        GroovyShell shell = new GroovyShell(cc);
        Whitelist wl = new AbstractWhitelist() {
            @Override public boolean permitsMethod(Method method, Object receiver, Object[] args) {
                return method.getDeclaringClass() == GroovyObject.class && method.getName().equals("getProperty") && receiver instanceof SpecialScript && args[0].equals("magic");
            }
        };
        assertEquals(42, GroovySandbox.run(shell.parse("magic"), wl));
        try {
            GroovySandbox.run(shell.parse("boring"), wl);
        } catch (MissingPropertyException x) {
            assertEquals("boring", x.getProperty());
        }
    }
    public static abstract class SpecialScript extends Script {
        @Override public Object getProperty(String property) {
            if (property.equals("magic")) {
                return 42;
            }
            return super.getProperty(property);
        }
    }

    @Issue("kohsuke/groovy-sandbox #16")
    @Test public void infiniteLoop() throws Exception {
        assertEvaluate(new BlanketWhitelist(), "abc", "def split = 'a b c'.split(' '); def b = new StringBuilder(); for (i = 0; i < split.length; i++) {println(i); b.append(split[i])}; b.toString()");
    }

    @Issue("JENKINS-25118")
    @Test public void primitiveTypes() throws Exception {
        try {
            assertEvaluate(new ProxyWhitelist(), "should fail", "'123'.charAt(1);");
        } catch (RejectedAccessException x) {
            assertNotNull(x.toString(), x.getSignature());
        }
        assertEvaluate(new StaticWhitelist("method java.lang.CharSequence charAt int"), '2', "'123'.charAt(1);");
    }

    @Test public void ambiguousOverloads() {
        // Groovy selects one of these. How, I do not know.
        assertEvaluate(new AnnotatedWhitelist(), true, Ambiguity.class.getName() + ".m(null)");
    }
    public static final class Ambiguity {
        @Whitelisted public static boolean m(String x) {return true;}
        @Whitelisted public static boolean m(URL x) {return true;}
    }

    @Test public void regexps() throws Exception {
        assertEvaluate(new GenericWhitelist(), "goodbye world", "def text = 'hello world'; def matcher = text =~ 'hello (.+)'; matcher ? \"goodbye ${matcher[0][1]}\" : 'fail'");
    }

    @Test public void splitAndJoin() throws Exception {
        assertEvaluate(new GenericWhitelist(), Collections.singletonMap("part0", "one\ntwo"), "def list = [['one', 'two']]; def map = [:]; for (int i = 0; i < list.size(); i++) {map[\"part${i}\"] = list.get(i).join(\"\\n\")}; map");
    }

    public static class ClassWithInvokeMethod extends GroovyObjectSupport {
        @Override
        public Object invokeMethod(String name, Object args) {
            throw new IllegalStateException();
        }
    }

    @Test public void invokeMethod_vs_DefaultGroovyMethods() throws Exception {
        // Closure defines the invokeMethod method, and asBoolean is defined on DefaultGroovyMethods.
        // the method dispatching in this case is that c.asBoolean() resolves to DefaultGroovyMethods.asBoolean()
        // and not invokeMethod("asBoolean")

        // calling asBoolean shouldn't go through invokeMethod
        MetaMethod m1 = InvokerHelper.getMetaClass(ClassWithInvokeMethod.class).pickMethod("asBoolean",new Class[0]);
        assertNotNull(m1);
        assertTrue((Boolean) m1.invoke(new ClassWithInvokeMethod(), new Object[0]));

        // as such, it should be allowed so long as asBoolean is whitelisted
        assertEvaluate(
                new ProxyWhitelist(
                        new GenericWhitelist(),
                        new StaticWhitelist("new " + ClassWithInvokeMethod.class.getName())
                ),
                true,
                "def c = new " + ClassWithInvokeMethod.class.getCanonicalName() + "(); c.asBoolean()"
        );
    }

    @Test public void keywordsAndOperators() throws Exception {
        String script = IOUtils.toString(this.getClass().getResourceAsStream("SandboxInterceptorTest/all.groovy"));
        assertEvaluate(new GenericWhitelist(), null, script);
    }

    @Issue("JENKINS-31234")
    @Test public void calendarGetInstance() throws Exception {
        assertEvaluate(new GenericWhitelist(), true, "Calendar.getInstance().get(Calendar.DAY_OF_MONTH) < 32");
        assertEvaluate(new GenericWhitelist(), true, "Calendar.instance.get(Calendar.DAY_OF_MONTH) < 32");
    }

    @Issue("JENKINS-31701")
    @Test public void primitiveWidening() throws Exception {
        assertEvaluate(new AnnotatedWhitelist(), 4L, SandboxInterceptorTest.class.getName() + ".usePrimitive(2)");
    }
    @Whitelisted public static long usePrimitive(long x) {
        return x + 2;
    }

    @Issue("JENKINS-32211")
    @Test public void tokenize() throws Exception {
        assertEvaluate(new GenericWhitelist(), 3, "'foo bar baz'.tokenize().size()");
        assertEvaluate(new GenericWhitelist(), 3, "'foo bar baz'.tokenize(' ').size()");
        assertEvaluate(new GenericWhitelist(), 3, "'foo bar baz'.tokenize('ba').size()");
    }

    private static void assertEvaluate(Whitelist whitelist, final Object expected, final String script) {
        final GroovyShell shell = new GroovyShell(GroovySandbox.createSecureCompilerConfiguration());
        Object actual = GroovySandbox.run(shell.parse(script), whitelist);
        if (actual instanceof GString) {
            actual = actual.toString(); // for ease of comparison
        }
        assertEquals(expected, actual);
        actual = new GroovyShell().evaluate(script);
        if (actual instanceof GString) {
            actual = actual.toString();
        }
        assertEquals("control case", expected, actual);
    }

    private static void assertRejected(Whitelist whitelist, String expectedSignature, String script) {
        try {
            assertEvaluate(whitelist, "should be rejected", script);
        } catch (RejectedAccessException x) {
            assertEquals(x.getMessage(), expectedSignature, x.getSignature());
        }
    }

}
