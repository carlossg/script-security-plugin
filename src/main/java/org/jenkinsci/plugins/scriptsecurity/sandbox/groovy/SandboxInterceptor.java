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

import groovy.lang.MissingPropertyException;
import groovy.lang.Script;
import hudson.Functions;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nonnull;
import org.codehaus.groovy.runtime.DateGroovyMethods;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.EncodingGroovyMethods;
import org.codehaus.groovy.runtime.ProcessGroovyMethods;
import org.codehaus.groovy.runtime.SqlGroovyMethods;
import org.codehaus.groovy.runtime.SwingGroovyMethods;
import org.codehaus.groovy.runtime.XmlGroovyMethods;
import org.codehaus.groovy.tools.DgmConverter;
import org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.EnumeratingWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist;
import org.kohsuke.groovy.sandbox.GroovyInterceptor;

@SuppressWarnings("rawtypes")
final class SandboxInterceptor extends GroovyInterceptor {

    private final Whitelist whitelist;
    
    SandboxInterceptor(Whitelist whitelist) {
        this.whitelist = whitelist;
    }

    /** should be synchronized with {@link DgmConverter} */
    private static final Class<?>[] DGM_CLASSES = {
        DefaultGroovyMethods.class,
        SwingGroovyMethods.class,
        SqlGroovyMethods.class,
        XmlGroovyMethods.class,
        EncodingGroovyMethods.class,
        DateGroovyMethods.class,
        ProcessGroovyMethods.class,
    };

    @Override public Object onMethodCall(GroovyInterceptor.Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        Method m = GroovyCallSiteSelector.method(receiver, method, args);
        if (m == null) {
            if (receiver instanceof Number || (receiver instanceof String && method.equals("plus"))) {
                // Synthetic methods like Integer.plus(Integer).
                return super.onMethodCall(invoker, receiver, method, args);
            }

            // look for GDK methods
            Object[] selfArgs = new Object[args.length + 1];
            selfArgs[0] = receiver;
            System.arraycopy(args, 0, selfArgs, 1, args.length);
            for (Class<?> dgmClass : DGM_CLASSES) {
                Method dgmMethod = GroovyCallSiteSelector.staticMethod(dgmClass, method, selfArgs);
                if (dgmMethod != null) {
                    if (whitelist.permitsStaticMethod(dgmMethod, selfArgs)) {
                        return super.onMethodCall(invoker, receiver, method, args);
                    } else {
                        throw StaticWhitelist.rejectStaticMethod(dgmMethod);
                    }
                }
            }

            // if no matching method, look for catchAll "invokeMethod"
            try {
                receiver.getClass().getMethod("invokeMethod", String.class, Object.class);
                return onMethodCall(invoker,receiver,"invokeMethod",method,args);
            } catch (NoSuchMethodException e) {
                // fall through
            }

            throw new RejectedAccessException("unclassified method " + EnumeratingWhitelist.getName(receiver.getClass()) + " " + method + printArgumentTypes(args));
        } else if (whitelist.permitsMethod(m, receiver, args)) {
            return super.onMethodCall(invoker, receiver, method, args);
        } else if (method.equals("invokeMethod") && args.length == 2 && args[0] instanceof String && args[1] instanceof Object[]) {
            throw StaticWhitelist.rejectMethod(m, EnumeratingWhitelist.getName(receiver.getClass()) + " " + args[0] + printArgumentTypes((Object[]) args[1]));
        } else {
            throw StaticWhitelist.rejectMethod(m);
        }
    }

    @Override public Object onNewInstance(GroovyInterceptor.Invoker invoker, Class receiver, Object... args) throws Throwable {
        Constructor<?> c = GroovyCallSiteSelector.constructor(receiver, args);
        if (c == null) {
            throw new RejectedAccessException("unclassified new " + EnumeratingWhitelist.getName(receiver) + printArgumentTypes(args));
        } else if (whitelist.permitsConstructor(c, args)) {
            return super.onNewInstance(invoker, receiver, args);
        } else {
            throw StaticWhitelist.rejectNew(c);
        }
    }

    @Override public Object onStaticCall(GroovyInterceptor.Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        Method m = GroovyCallSiteSelector.staticMethod(receiver, method, args);
        if (m == null) {
            // TODO consider DefaultGroovyStaticMethods
            throw new RejectedAccessException("unclassified staticMethod " + EnumeratingWhitelist.getName(receiver) + " " + method + printArgumentTypes(args));
        } else if (whitelist.permitsStaticMethod(m, args)) {
            return super.onStaticCall(invoker, receiver, method, args);
        } else {
            throw StaticWhitelist.rejectStaticMethod(m);
        }
    }

    @Override public Object onSetProperty(GroovyInterceptor.Invoker invoker, final Object receiver, final String property, Object value) throws Throwable {
        if (receiver instanceof Script && !property.equals("binding") && !property.equals("metaClass")) {
            return super.onSetProperty(invoker, receiver, property, value);
        }
        Rejector rejector = null; // avoid creating exception objects unless and until thrown
        // https://github.com/kohsuke/groovy-sandbox/issues/7 need to explicitly check for getters and setters:
        Object[] valueArg = new Object[] {value};
        String setter = "set" + Functions.capitalize(property);
        final Method setterMethod = GroovyCallSiteSelector.method(receiver, setter, valueArg);
        if (setterMethod != null) {
            if (whitelist.permitsMethod(setterMethod, receiver, valueArg)) {
                return super.onSetProperty(invoker, receiver, property, value);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectMethod(setterMethod);
                    }
                };
            }
        }
        Object[] propertyValueArgs = new Object[] {property, value};
        final Method setPropertyMethod = GroovyCallSiteSelector.method(receiver, "setProperty", propertyValueArgs);
        if (setPropertyMethod != null) {
            if (whitelist.permitsMethod(setPropertyMethod, receiver, propertyValueArgs)) {
                return super.onSetProperty(invoker, receiver, property, value);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectMethod(setPropertyMethod, receiver.getClass().getName() + "." + property);
                    }
                };
            }
        }
        final Field instanceField = GroovyCallSiteSelector.field(receiver, property);
        if (instanceField != null) {
            if (whitelist.permitsFieldSet(instanceField, receiver, value)) {
                return super.onSetProperty(invoker, receiver, property, value);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectField(instanceField);
                    }
                };
            }
        }
        if (receiver instanceof Class) {
            final Method staticSetterMethod = GroovyCallSiteSelector.staticMethod((Class) receiver, setter, valueArg);
            if (staticSetterMethod != null) {
                if (whitelist.permitsStaticMethod(staticSetterMethod, valueArg)) {
                    return super.onSetProperty(invoker, receiver, property, value);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticMethod(staticSetterMethod);
                        }
                    };
                }
            }
            final Field staticField = GroovyCallSiteSelector.staticField((Class) receiver, property);
            if (staticField != null) {
                if (whitelist.permitsStaticFieldSet(staticField, value)) {
                    return super.onSetProperty(invoker, receiver, property, value);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticField(staticField);
                        }
                    };
                }
            }
        }
        throw rejector != null ? rejector.reject() : unclassifiedField(receiver, property);
    }

    @Override public Object onGetProperty(GroovyInterceptor.Invoker invoker, final Object receiver, final String property) throws Throwable {
        MissingPropertyException mpe = null;
        if (receiver instanceof Script) { // SimpleTemplateEngine "out" variable, and anything else added in a binding
            try {
                ((Script) receiver).getBinding().getVariable(property); // do not let it go to Script.super.getProperty
                return super.onGetProperty(invoker, receiver, property);
            } catch (MissingPropertyException x) {
                mpe = x; // throw only if we are not whitelisted
            }
        }
        if (property.equals("length") && receiver.getClass().isArray()) {
            return super.onGetProperty(invoker, receiver, property);
        }
        Rejector rejector = null;
        Object[] noArgs = new Object[] {};
        String getter = "get" + Functions.capitalize(property);
        final Method getterMethod = GroovyCallSiteSelector.method(receiver, getter, noArgs);
        if (getterMethod != null) {
            if (whitelist.permitsMethod(getterMethod, receiver, noArgs)) {
                return super.onGetProperty(invoker, receiver, property);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectMethod(getterMethod);
                    }
                };
            }
        }
        String booleanGetter = "is" + Functions.capitalize(property);
        final Method booleanGetterMethod = GroovyCallSiteSelector.method(receiver, booleanGetter, noArgs);
        if (booleanGetterMethod != null && booleanGetterMethod.getReturnType() == boolean.class) {
            if (whitelist.permitsMethod(booleanGetterMethod, receiver, noArgs)) {
                return super.onGetProperty(invoker, receiver, property);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectMethod(booleanGetterMethod);
                    }
                };
            }
        }
        // look for GDK methods
        Object[] selfArgs = new Object[] {receiver};
        for (Class<?> dgmClass : DGM_CLASSES) {
            final Method dgmGetterMethod = GroovyCallSiteSelector.staticMethod(dgmClass, getter, selfArgs);
            if (dgmGetterMethod != null) {
                if (whitelist.permitsStaticMethod(dgmGetterMethod, selfArgs)) {
                    return super.onGetProperty(invoker, receiver, property);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override
                        public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticMethod(dgmGetterMethod);
                        }
                    };
                }
            }
            final Method dgmBooleanGetterMethod = GroovyCallSiteSelector.staticMethod(dgmClass, booleanGetter, selfArgs);
            if (dgmBooleanGetterMethod != null && dgmBooleanGetterMethod.getReturnType() == boolean.class) {
                if (whitelist.permitsStaticMethod(dgmBooleanGetterMethod, selfArgs)) {
                    return super.onGetProperty(invoker, receiver, property);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override
                        public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticMethod(dgmBooleanGetterMethod);
                        }
                    };
                }
            }
        }
        final Field instanceField = GroovyCallSiteSelector.field(receiver, property);
        if (instanceField != null) {
            if (whitelist.permitsFieldGet(instanceField, receiver)) {
                return super.onGetProperty(invoker, receiver, property);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectField(instanceField);
                    }
                };
            }
        }
        // GroovyObject property access
        Object[] propertyArg = new Object[] {property};
        final Method getPropertyMethod = GroovyCallSiteSelector.method(receiver, "getProperty", propertyArg);
        if (getPropertyMethod != null) {
            if (whitelist.permitsMethod(getPropertyMethod, receiver, propertyArg)) {
                return super.onGetProperty(invoker, receiver, property);
            } else if (rejector == null) {
                rejector = new Rejector() {
                    @Override public RejectedAccessException reject() {
                        return StaticWhitelist.rejectMethod(getPropertyMethod, receiver.getClass().getName() + "." + property);
                    }
                };
            }
        }
        if (receiver instanceof Class) {
            final Method staticGetterMethod = GroovyCallSiteSelector.staticMethod((Class) receiver, getter, noArgs);
            if (staticGetterMethod != null) {
                if (whitelist.permitsStaticMethod(staticGetterMethod, noArgs)) {
                    return super.onGetProperty(invoker, receiver, property);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticMethod(staticGetterMethod);
                        }
                    };
                }
            }
            final Method staticBooleanGetterMethod = GroovyCallSiteSelector.staticMethod((Class) receiver, booleanGetter, noArgs);
            if (staticBooleanGetterMethod != null && staticBooleanGetterMethod.getReturnType() == boolean.class) {
                if (whitelist.permitsStaticMethod(staticBooleanGetterMethod, noArgs)) {
                    return super.onGetProperty(invoker, receiver, property);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticMethod(staticBooleanGetterMethod);
                        }
                    };
                }
            }
            final Field staticField = GroovyCallSiteSelector.staticField((Class) receiver, property);
            if (staticField != null) {
                if (whitelist.permitsStaticFieldGet(staticField)) {
                    return super.onGetProperty(invoker, receiver, property);
                } else if (rejector == null) {
                    rejector = new Rejector() {
                        @Override public RejectedAccessException reject() {
                            return StaticWhitelist.rejectStaticField(staticField);
                        }
                    };
                }
            }
        }
        if (mpe != null) {
            throw mpe;
        }
        throw rejector != null ? rejector.reject() : unclassifiedField(receiver, property);
    }

    private static RejectedAccessException unclassifiedField(Object receiver, String property) {
        return new RejectedAccessException("unclassified field " + EnumeratingWhitelist.getName(receiver.getClass()) + " " + property);
    }

    // TODO Java 8: @FunctionalInterface
    private interface Rejector {
        @Nonnull RejectedAccessException reject();
    }

    // TODO consider whether it is useful to override onGet/SetArray/Attribute

    private static String printArgumentTypes(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (Object arg : args) {
            b.append(' ');
            b.append(arg == null ? "null" : EnumeratingWhitelist.getName(arg.getClass()));
        }
        return b.toString();
    }

}
