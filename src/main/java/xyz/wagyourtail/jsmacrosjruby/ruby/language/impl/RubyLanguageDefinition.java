package xyz.wagyourtail.jsmacrosjruby.ruby.language.impl;

import org.jruby.RubyException;
import org.jruby.embed.EvalFailedException;
import org.jruby.embed.ScriptingContainer;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.backtrace.RubyStackTraceElement;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.config.ScriptTrigger;
import xyz.wagyourtail.jsmacros.core.event.BaseEvent;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.language.ContextContainer;
import xyz.wagyourtail.jsmacros.core.language.ScriptContext;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public class RubyLanguageDefinition extends BaseLanguage<ScriptingContainer> {
    public static final ScriptingContainer globalInstance = new ScriptingContainer();
    public RubyLanguageDefinition(String extension, Core runner) {
        super(extension, runner);
    }
    
    protected void runInstance(ContextContainer<ScriptingContainer> ctx, Executor e, Path cwd) throws Exception {
        ctx.getCtx().setContext(globalInstance);
        
        globalInstance.setCurrentDirectory(cwd.toString());
        retrieveLibs(ctx).forEach((k,v) -> globalInstance.put(k.toLowerCase(Locale.ROOT), v));

        e.accept(globalInstance);

        if (((RubyScriptContext) ctx.getCtx()).nonGCdMethodWrappers.get() == 0) {
            ctx.getCtx().closeContext();
        }
    }
    
    @Override
    protected void exec(ContextContainer<ScriptingContainer> ctx, ScriptTrigger macro, File file, BaseEvent event) throws Exception {
        runInstance(ctx, instance -> {
            instance.put("event", event);
            instance.put("file", file);
            instance.put("context", ctx);
    
            instance.runScriptlet(new FileReader(file), file.getAbsolutePath());
        }, file.getParentFile().toPath());
        
    }
    
    @Override
    public void exec(ContextContainer<ScriptingContainer> ctx, String script, Map<String, Object> globals, Path currentDir) throws Exception {
        runInstance(ctx, instance -> {
            globals.forEach(instance::put);
            instance.put("context", ctx);
            instance.runScriptlet(script);
        }, currentDir);
    }
    
    @Override
    public BaseWrappedException<?> wrapException(Throwable ex) {
        if (ex instanceof EvalFailedException) {
            Throwable cause = ex.getCause();
            if (cause instanceof RaiseException) {
                RubyException e = ((RaiseException) ex.getCause()).getException();
                Iterator<StackTraceElement> elements = Arrays.stream(e.getBacktraceElements()).map(RubyStackTraceElement::asStackTraceElement).iterator();
                return new BaseWrappedException<>(e, e.getMessageAsJavaString(), null, elements.hasNext() ? traceStack(elements.next(), elements) : null);
            } else {
                Iterator<StackTraceElement> elements = Arrays.stream(cause.getStackTrace()).iterator();
                return new BaseWrappedException<>(cause, cause.getClass().getName() + ": " + cause.getMessage(), null, elements.hasNext() ? traceStack(elements.next(), elements) : null);
            }
        }
        return null;
    }
    
    @Override
    public ScriptContext<ScriptingContainer> createContext(BaseEvent event) {
        return new RubyScriptContext(event);
    }
    
    private BaseWrappedException<StackTraceElement> traceStack(StackTraceElement current, Iterator<StackTraceElement> elements) {
        if (current.getClassName().equals("org.jruby.embed.internal.EmbedEvalUnitImpl")) return null;
        if (current.getClassName().startsWith("org.jruby")) return elements.hasNext() ? traceStack(elements.next(), elements) : null;
        BaseWrappedException.SourceLocation loc;
        if (current.getClassName().equals("RUBY")) {
            loc = new BaseWrappedException.GuestLocation(new File(current.getFileName()), -1, -1, current.getLineNumber(), -1);
        } else {
            loc = new BaseWrappedException.HostLocation(current.getClassName() + " " + current.getLineNumber());
        }
        return new BaseWrappedException<>(current, current.getMethodName(), loc, elements.hasNext() ? traceStack(elements.next(), elements) : null);
    }
    
    private interface Executor {
        void accept(ScriptingContainer instance) throws Exception;
    }
}
