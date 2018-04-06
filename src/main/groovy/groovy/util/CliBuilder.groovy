/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package groovy.util

import groovy.cli.CliBuilderException
import groovy.cli.Option
import groovy.cli.TypedOption
import groovy.cli.Unparsed
import groovy.transform.Undefined
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.MetaClassHelper
import org.codehaus.groovy.runtime.StringGroovyMethods
import picocli.CommandLine
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Model.IGetter
import picocli.CommandLine.Model.ISetter
import picocli.CommandLine.Model.OptionSpec
import picocli.CommandLine.Model.PositionalParamSpec
import picocli.CommandLine.ParseResult

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Provides a builder to assist the processing of command line arguments.
 * Two styles are supported: dynamic api style (declarative method calls provide a mini DSL for describing options)
 * and annotation style (annotations on an interface or class describe options).
 * <p>
 * <b>Dynamic api style</b>
 * <p>
 * Typical usage (emulate partial arg processing of unix command: ls -alt *.groovy):
 * <pre>
 * def cli = new CliBuilder(usage:'ls')
 * cli.a('display all files')
 * cli.l('use a long listing format')
 * cli.t('sort by modification time')
 * def options = cli.parse(args)
 * assert options // would be null (false) on failure
 * assert options.arguments() == ['*.groovy']
 * assert options.a && options.l && options.t
 * </pre>
 * The usage message for this example (obtained using <code>cli.usage()</code>) is shown below:
 * <pre>
 * usage: ls
 *  -a   display all files
 *  -l   use a long listing format
 *  -t   sort by modification time
 * </pre>
 * An underlying parser that supports what is called argument 'bursting' is used
 * by default. Bursting would convert '-alt' into '-a -l -t' provided no long
 * option exists with value 'alt' and provided that none of 'a', 'l' or 't'
 * takes an argument (in fact the last one is allowed to take an argument).
 * The bursting behavior can be turned off by configuring the underlying parser.
 * The simplest way to achieve this is by setting the posix property on the CliBuilder
 * to false, i.e. include {@code posix: false} in the constructor call.
 * <p>
 * Another example (partial emulation of arg processing for 'ant' command line):
 * <pre>
 * def cli = new CliBuilder(usage:'ant [options] [targets]',
 *                          optionListHeading:'Options:%n') // TODO was header:'Options:'
 * cli.help('print this message')
 * cli.logfile(type:File, argName:'file', 'use given file for log')
 * cli.D(type:Map, argName:'property=value', 'use value for given property')
 * def options = cli.parse(args)
 * ...
 * </pre>
 * Usage message would be:
 * <pre>
 * usage: ant [options] [targets]
 * Options:
 *  -D=&lt;property=value>      use value for given property
 *     -help                 print this message
 *     -logfile=&lt;file>       use given file for log
 * </pre>
 * And if called with the following arguments '-logfile foo -Dbar=baz target'
 * then the following assertions would be true:
 * <pre>
 * assert options // would be null (false) on failure
 * assert options.arguments() == ['target']
 * assert options.Ds == ['bar', 'baz']
 * assert options.logfile == 'foo'
 * </pre>
 * Note the use of some special notation. By adding 's' onto an option
 * that may appear multiple times and has an argument or as in this case
 * uses a valueSeparator to separate multiple argument values
 * causes the list of associated argument values to be returned.
 * <p>
 * Another example showing long options (partial emulation of arg processing for 'curl' command line):
 * <pre>
 * def cli = new CliBuilder(usage:'curl [options] &lt;url&gt;')
 * cli._(longOpt:'basic', 'Use HTTP Basic Authentication')
 * cli.d(longOpt:'data', args:1, argName:'data', 'HTTP POST data')
 * cli.G(longOpt:'get', 'Send the -d data with a HTTP GET')
 * cli.q('If used as the first parameter disables .curlrc')
 * cli._(longOpt:'url', args:1, argName:'URL', 'Set URL to work with')
 * </pre>
 * Which has the following usage message:
 * <pre>
 * usage: curl [options] &lt;url>
 *     --basic         Use HTTP Basic Authentication
 *  -d,--data &lt;data>   HTTP POST data
 *  -G,--get           Send the -d data with a HTTP GET
 *  -q                 If used as the first parameter disables .curlrc
 *     --url &lt;URL>     Set URL to work with
 * </pre>
 * This example shows a common convention. When mixing short and long names, the
 * short names are often one character in size. One character options with
 * arguments don't require a space between the option and the argument, e.g.
 * <code>-Ddebug=true</code>. The example also shows
 * the use of '_' when no short option is applicable.
 * <p>
 * Also note that '_' was used multiple times. This is supported but if
 * any other shortOpt or any longOpt is repeated, then the behavior is undefined.
 * <p>
 * Short option names may not contain a hyphen. If a long option name contains a hyphen, e.g. '--max-wait' then you can either
 * use the long hand method call <code>options.hasOption('max-wait')</code> or surround
 * the option name in quotes, e.g. <code>options.'max-wait'</code>.
 * <p>
 * Although CliBuilder on the whole hides away the underlying library used
 * for processing the arguments, it does provide some hooks which let you
 * make use of the underlying library directly should the need arise. For
 * example, the last two lines of the 'curl' example above could be replaced
 * with the following:
 * <pre>
 * import picocli.CommandLine.Model.*
 * ... as before ...
 * cli << OptionSpec.builder('-q').
 *                      description('If used as the first parameter disables .curlrc').build()
 * cli << OptionSpec.builder('--url').type(URL.class).paramLabel('&lt;URL>').
 *                      description('Set URL to work with').build()
 * ...
 * </pre>
 *
 * <p>
 * <b>Supported Option Properties</b>:
 * <pre>
 *   argName:        String
 *   longOpt:        String
 *   args:           int or String
 *   optionalArg:    boolean
 *   required:       boolean
 *   type:           Class
 *   valueSeparator: char
 *   convert:        Closure
 *   defaultValue:   String
 * </pre>
 * TODO See {@link org.apache.commons.cli.Option} for the meaning of most of these properties
 * and {@link CliBuilderTest} for further examples.
 * <p>
 * <b>@-files</b>
 * <p>
 * CliBuilder also supports Argument File processing. If an argument starts with
 * an '@' character followed by a filename, then the contents of the file with name
 * filename are placed into the command line. The feature can be turned off by
 * setting expandArgumentFiles to false. If turned on, you can still pass a real
 * parameter with an initial '@' character by escaping it with an additional '@'
 * symbol, e.g. '@@foo' will become '@foo' and not be subject to expansion. As an
 * example, if the file temp.args contains the content:
 * <pre>
 * -arg1
 * paramA
 * paramB paramC
 * </pre>
 * Then calling the command line with:
 * <pre>
 * someCommand @temp.args -arg2 paramD
 * </pre>
 * Is the same as calling this:
 * <pre>
 * someCommand -arg1 paramA paramB paramC -arg2 paramD
 * </pre>
 * This feature is particularly useful on operating systems which place limitations
 * on the size of the command line (e.g. Windows). The feature is similar to
 * the 'Command Line Argument File' processing supported by javadoc and javac.
 * Consult the corresponding documentation for those tools if you wish to see further examples.
 * <p>
 * <b>Annotation style with an interface</b>
 * <p>
 * With this style an interface is defined containing an annotated method for each option.
 * It might look like this (following roughly the earlier 'ls' example):
 * <pre>
 * import groovy.cli.Option
 * import groovy.cli.Unparsed
 *
 * interface OptionInterface {
 *     @{@link groovy.cli.Option}(shortName='a', description='display all files') boolean all()
 *     @{@link groovy.cli.Option}(shortName='l', description='use a long listing format') boolean longFormat()
 *     @{@link groovy.cli.Option}(shortName='t', description='sort by modification time') boolean time()
 *     @{@link groovy.cli.Unparsed} List remaining()
 * }
 * </pre>
 * Then this description is supplied to CliBuilder during parsing, e.g.:
 * <pre>
 * def args = '-alt *.groovy'.split() // normally from commandline itself
 * def cli = new CliBuilder(usage:'ls')
 * def options = cli.parseFromSpec(OptionInterface, args)
 * assert options.remaining() == ['*.groovy']
 * assert options.all() && options.longFormat() && options.time()
 * </pre>
 * <p>
 * <b>Annotation style with a class</b>
 * <p>
 * With this style a user-supplied instance is used. Annotations on that instance's class
 * members (properties and setter methods) indicate how to set options and provide the option details
 * using annotation attributes.
 * It might look like this (again using the earlier 'ls' example):
 * <pre>
 * import groovy.cli.Option
 * import groovy.cli.Unparsed
 *
 * class OptionClass {
 *     @{@link groovy.cli.Option}(shortName='a', description='display all files') boolean all
 *     @{@link groovy.cli.Option}(shortName='l', description='use a long listing format') boolean longFormat
 *     @{@link groovy.cli.Option}(shortName='t', description='sort by modification time') boolean time
 *     @{@link groovy.cli.Unparsed} List remaining
 * }
 * </pre>
 * Then this description is supplied to CliBuilder during parsing, e.g.:
 * <pre>
 * def args = '-alt *.groovy'.split() // normally from commandline itself
 * def cli = new CliBuilder(usage:'ls')
 * def options = new OptionClass()
 * cli.parseFromInstance(options, args)
 * assert options.remaining == ['*.groovy']
 * assert options.all && options.longFormat && options.time
 * </pre>
 */
class CliBuilder {
    /**
     * Usage summary displayed as the first line when <code>cli.usage()</code> is called.
     */
    String usage = 'groovy'

    /**
     * This property allows customizing the program name displayed in the synopsis when <code>cli.usage()</code> is called.
     * Ignored if the {@link #usage} property is set.
     * @since 2.5
     */
    String name = 'groovy'

    /**
     * To disallow clustered POSIX short options, set this to false.
     */
    Boolean posix = null

    /**
     * Whether arguments of the form '{@code @}<i>filename</i>' will be expanded into the arguments contained within the file named <i>filename</i> (default true).
     */
    boolean expandArgumentFiles = true

    /**
     * Indicates that argument processing should continue for all arguments even
     * if arguments not recognized as options are encountered (default true).
     */
    boolean stopAtNonOption = true

    /**
     * The PrintWriter to write the {@linkplain #usage} help message to.
     * Defaults to stdout but you can provide your own PrintWriter if desired.
     */
    PrintWriter writer = new PrintWriter(System.out)

    /**
     * The PrintWriter to write to when invalid user input was provided to
     * the {@link #parse(java.lang.String[])} method.
     * Defaults to stderr but you can provide your own PrintWriter if desired.
     * @since 2.5
     */
    PrintWriter errorWriter = new PrintWriter(System.err)

    /**
     * Optional additional message for usage; displayed after the usage summary
     * but before the options are displayed.
     */
    String header = null

    /**
     * Optional additional message for usage; displayed after the options.
     */
    String footer = null

    /**
     * Allows customisation of the usage message width.
     */
    int width = CommandLine.Model.UsageMessageSpec.DEFAULT_USAGE_WIDTH

    /**
     * Not normally accessed directly but full access to underlying model if needed.
     * @since 2.5
     */
    CommandSpec commandSpec = CommandSpec.create();

    Map<String, TypedOption> savedTypeOptions = new HashMap<String, TypedOption>()

    void setUsage(String usage) {
        this.usage = usage
        commandSpec.usageMessage().customSynopsis(usage)
    }

    /**
     * For backwards compatibility reasons, if a custom {@code writer} is set, this sets
     * both the {@link #writer} and the {@link #errorWriter} to the specified writer.
     * @param writer the writer to initialize both the {@code writer} and the {@code errorWriter} to
     */
    void setWriter(PrintWriter writer) {
        this.writer = writer
        this.errorWriter = writer
    }

    public <T> TypedOption<T> option(Map args, Class<T> type, String description) {
        def name = args.opt ?: '_'
        args.type = type
        args.remove('opt')
        "$name"(args, description)
    }

    /**
     * Internal method: Detect option specification method calls.
     */
    def invokeMethod(String name, Object args) {
        if (args instanceof Object[]) {
            if (args.size() == 1 && (args[0] instanceof String || args[0] instanceof GString)) {
                def option = option(name, [:], args[0]) // args[0] is description
                commandSpec.addOption(option)
                return create(option, null, null, null)
            }
            if (args.size() == 1 && args[0] instanceof OptionSpec && name == 'leftShift') {
                OptionSpec option = args[0] as OptionSpec
                commandSpec.addOption(option)
                return create(option, null, null, null)
            }
            if (args.size() == 2 && args[0] instanceof Map) {
                Map m = args[0] as Map
                if (m.type && !(m.type instanceof Class)) {
                    throw new CliBuilderException("'type' must be a Class")
                }
                def option = option(name, m, args[1])
                commandSpec.addOption(option)
                return create(option, option.type(), option.defaultValue(), option.converters())
            }
        }
        return InvokerHelper.getMetaClass(this).invokeMethod(this, name, args)
    }

    private TypedOption create(OptionSpec o, Class theType, defaultValue, convert) {
        String opt = o.names().sort { a, b -> a.length() - b.length() }.first()
        opt = opt?.length() == 2 ? opt.substring(1) : null

        String longOpt = o.names().sort { a, b -> b.length() - a.length() }.first()
        longOpt = longOpt?.startsWith("--") ? longOpt.substring(2) : null

        Map<String, Object> result = new TypedOption<Object>()
        if (opt != null) result.put("opt", opt)
        result.put("longOpt", longOpt)
        result.put("cliOption", o)
        if (defaultValue) {
            result.put("defaultValue", defaultValue)
        }
        if (convert) {
            if (theType) {
                throw new CliBuilderException("You can't specify 'type' when using 'convert'")
            }
            result.put("convert", convert)
            result.put("type", convert instanceof Class ? convert : convert.getClass())
        } else {
            result.put("type", theType)
        }
        savedTypeOptions[longOpt ?: opt] = result
        result
    }

    /**
     * Make options accessible from command line args with parser.
     * Returns null on bad command lines after displaying usage message.
     */
    OptionAccessor parse(args) {
        if (!System.getProperty("picocli.trace")) { // suppress warnings
            System.setProperty("picocli.trace", "OFF")
        }
        commandSpec.parser()
                .overwrittenOptionsAllowed(true)
                //.unmatchedArgumentsAllowed(stopAtNonOption) // TODO check commons-cli semantics
                .stopAtPositional(stopAtNonOption)
                .expandAtFiles(expandArgumentFiles)
                .posixClusteredShortOptionsAllowed(posix ?: true)
                .arityRestrictsCumulativeSize(true)
        commandSpec.name(name).usageMessage()
                .description(header)
                .footer(footer)
                .width(width)
        if (stopAtNonOption && commandSpec.positionalParameters().empty) {
            commandSpec.addPositional(PositionalParamSpec.builder().type(String[]).hidden(true).build())
        }
        def commandLine = new CommandLine(commandSpec)
        try {
            def accessor = new OptionAccessor(commandLine.parseArgs(args as String[]))
            accessor.savedTypeOptions = savedTypeOptions
            return accessor
        } catch (CommandLine.ParameterException pe) {
            errorWriter.println("error: " + pe.message)
            printUsage(pe.commandLine, errorWriter)
            return null
        }
    }

    /**
     * Prints the usage message with the specified {@link #header header}, {@link #footer footer} and {@link #width width}
     * to the specified {@link #writer writer} (default: System.out).
     */
    void usage() {
        printUsage(commandSpec.commandLine(), writer)
    }

    private void printUsage(CommandLine commandLine, PrintWriter pw) {
        commandLine.usage(pw)
        pw.flush()
    }

    /**
     * Given an interface containing members with annotations, derive
     * the options specification.
     *
     * @param optionsClass
     * @param args
     * @return an instance containing the processed options
     */
    public <T> T parseFromSpec(Class<T> optionsClass, String[] args) {
        def cliOptions = [:]
        addOptionsFromAnnotations(optionsClass, cliOptions, true)
        addPositionalsFromAnnotations(optionsClass, cliOptions, true)
        parse(args)
        cliOptions as T
    }

    /**
     * Given an instance containing members with annotations, derive
     * the options specification.
     *
     * @param optionInstance
     * @param args
     * @return the options instance populated with the processed options
     */
    public <T> T parseFromInstance(T optionInstance, args) {
        addOptionsFromAnnotations(optionInstance.getClass(), optionInstance, false)
        addPositionalsFromAnnotations(optionInstance.getClass(), optionInstance, false)
        parse(args)
        optionInstance
    }

    private void addOptionsFromAnnotations(Class optionClass, Object target, boolean isCoercedMap) {
        optionClass.methods.findAll{ it.getAnnotation(Option) }.each { Method m ->
            Option annotation = m.getAnnotation(Option)
            ArgSpecAttributes attributes = extractAttributesFromMethod(m, isCoercedMap, target)
            commandSpec.addOption(createOptionSpec(annotation, attributes, target))
        }
        def optionFields = optionClass.declaredFields.findAll { it.getAnnotation(Option) }
        if (optionClass.isInterface() && !optionFields.isEmpty()) {
            throw new CliBuilderException("@Option only allowed on methods in interface " + optionClass.simpleName)
        }
        optionFields.each { Field f ->
            Option annotation = f.getAnnotation(Option)
            ArgSpecAttributes attributes = extractAttributesFromField(f, target)
            commandSpec.addOption(createOptionSpec(annotation, attributes, target))
        }
    }

    private void addPositionalsFromAnnotations(Class optionClass, Object target, boolean isCoercedMap) {
        optionClass.methods.findAll{ it.getAnnotation(Unparsed) }.each { Method m ->
            Unparsed annotation = m.getAnnotation(Unparsed)
            ArgSpecAttributes attributes = extractAttributesFromMethod(m, isCoercedMap, target)
            commandSpec.addPositional(createPositionalParamSpec(annotation, attributes, target))
        }
        def optionFields = optionClass.declaredFields.findAll { it.getAnnotation(Unparsed) }
        if (optionClass.isInterface() && !optionFields.isEmpty()) {
            throw new CliBuilderException("@Unparsed only allowed on methods in interface " + optionClass.simpleName)
        }
        optionFields.each { Field f ->
            Unparsed annotation = f.getAnnotation(Unparsed)
            ArgSpecAttributes attributes = extractAttributesFromField(f, target)
            commandSpec.addPositional(createPositionalParamSpec(annotation, attributes, target))
        }
    }

    private static class ArgSpecAttributes {
        Class type
        Class[] auxiliaryTypes
        String label
        IGetter getter
        ISetter setter
    }

    private ArgSpecAttributes extractAttributesFromMethod(Method m, boolean isCoercedMap, target) {
        Class type = isCoercedMap ? m.returnType : (m.parameterTypes.size() > 0 ? m.parameterTypes[0] : m.returnType)
        type = type && type == Void.TYPE ? null : type

        Class[] auxTypes = null // TODO extract generic types like List<Integer>, Map<Integer,Double>

        // If the method is a real setter, we can't invoke it to get its value,
        // so instead we need to keep track of its current value ourselves.
        // Additionally, implementation classes may annotate _getter_ methods with @Option;
        // if the getter returns a Collection or Map, picocli will add parsed values to it.
        def currentValue = initialValue(type, m, target, isCoercedMap)
        if (isCoercedMap) {
            target[m.name] = { currentValue }
        }
        def getter = { currentValue }
        def setter = {
            def old = currentValue
            currentValue = it
            if (isCoercedMap) {
                target[m.name] = { currentValue }
            } else if (m.parameterTypes.size() > 0) {
                m.invoke(target, [currentValue].toArray())
            }
            return old
        }
        def label = m.name.startsWith("set") || m.name.startsWith("get") ? MetaClassHelper.convertPropertyName(m.name.substring(3)) : m.name
        new ArgSpecAttributes(type: type, auxiliaryTypes: auxTypes, label: label, getter: getter, setter: setter)
    }

    private Object initialValue(Class<?> cls, Method m, Object target, boolean isCoercedMap) {
        if (m.parameterTypes.size() == 0 && m.returnType != Void.TYPE) {
            return isCoercedMap ? target[m.name] : m.invoke(target)
        }
        if (List.class.isAssignableFrom(cls)) { // TODO support other Collections in future
            return new ArrayList()
        }
        if (Map.class.isAssignableFrom(cls)) {
            return new LinkedHashMap()
        }
        null
    }

    private ArgSpecAttributes extractAttributesFromField(Field f, target) {
        def getter = {
            f.accessible = true
            f.get(target)
        }
        def setter = { newValue ->
            f.accessible = true
            def oldValue = f.get(target)
            f.set(target, newValue)
            oldValue
        }
        Class[] auxTypes = null // TODO extract generic types like List<Integer>, Map<Integer,Double>
        new ArgSpecAttributes(type: f.type, auxiliaryTypes: auxTypes, label: f.name, getter: getter, setter: setter)
    }

    private PositionalParamSpec createPositionalParamSpec(Unparsed unparsed, ArgSpecAttributes attr, Object target) {
        PositionalParamSpec.Builder builder = PositionalParamSpec.builder();

        CommandLine.Range arity = CommandLine.Range.valueOf("0..*")
        if (attr.type == Object) { attr.type = String[] }
        if (attr.type)           { builder.type(attr.type) } // cannot set type to null
        if (attr.auxiliaryTypes) { builder.auxiliaryTypes(attr.auxiliaryTypes) } // cannot set aux types to null
        builder.arity(arity)
        builder.paramLabel("<$attr.label>")
        builder.getter(attr.getter)
        builder.setter(attr.setter)
        builder.build()
    }

    private OptionSpec createOptionSpec(Option annotation, ArgSpecAttributes attr, Object target) {
        Map names = calculateNames(annotation.longName(), annotation.shortName(), attr.label)
        String arityString = extractArity(attr.type, annotation.optionalArg(), annotation.numberOfArguments(), annotation.numberOfArgumentsString(), names)
        CommandLine.Range arity = CommandLine.Range.valueOf(arityString)
        if (attr.type == Object && arity.max == 0) { attr.type = boolean }
        OptionSpec.Builder builder = OptionSpec.builder(hyphenate(names))
        if (attr.type)           { builder.type(attr.type) } // cannot set type to null
        if (attr.auxiliaryTypes) { builder.auxiliaryTypes(attr.auxiliaryTypes) } // cannot set aux types to null
        builder.arity(arity)
        builder.description(annotation.description())
        builder.splitRegex(annotation.valueSeparator())
        if (annotation.defaultValue()) { builder.defaultValue(annotation.defaultValue()) } // don't default picocli model to empty string
        builder.paramLabel("<$attr.label>")
        if (annotation.convert() != Undefined.CLASS) {
            if (annotation.convert() instanceof Class) {
                builder.converters(annotation.convert().newInstance(target, target) as ITypeConverter)
            }
        }
        builder.getter(attr.getter)
        builder.setter(attr.setter)
        builder.build()
    }

    private static String[] hyphenate(Map<String, String> names) {
        names.values().findAll { it && it != "_" }.collect { it.length() == 1 ? "-$it" : ["-$it", "--$it"] }.flatten().toArray()
    }

    private static String extractArity(Class<?> type, boolean optionalArg, int numberOfArguments, String numberOfArgumentsString, Map names) {
        if (optionalArg && (!type || !isMultiValue(type))) {
            throw new CliBuilderException("Attempted to set optional argument for single-value type on flag '${names.long ?: names.short}'")
        }
        if (numberOfArguments != 1 && numberOfArgumentsString) {
            throw new CliBuilderException("You can't specify both 'numberOfArguments' and 'numberOfArgumentsString' on flag '${names.long ?: names.short}'")
        }
        def isFlag = type.simpleName.toLowerCase() == 'boolean'
        String arity = "0"
        if (numberOfArgumentsString) {
            arity = optionalArg ? "0.." + (numberOfArgumentsString == "+" ? "*"    : numberOfArgumentsString)
                                :         (numberOfArgumentsString == "+" ? "1..*" : numberOfArgumentsString)
        } else {
            int argCount = isFlag ? 0 : numberOfArguments
            arity = optionalArg ? "0..$argCount" : argCount as String
        }
        if (arity == "0" && !(isFlag || type.name == 'java.lang.Object')) {
            throw new CliBuilderException("Flag '${names.long ?: names.short}' must be Boolean or Object")
        }
        arity
    }
    private static boolean isMultiValue(Class<?> cls) {
        cls.isArray() || Collection.class.isAssignableFrom(cls) || Map.class.isAssignableFrom(cls)
    }

    private Map calculateNames(String longName, String shortName, String label) {
        boolean useShort = longName == '_'
        if (longName == '_') longName = ""
        def result = longName ?: label
        [long: useShort ? "" : result, short: (useShort && !shortName) ? result : shortName]
    }

    // implementation details -------------------------------------
    /**
     * Internal method: How to create an OptionSpec from the specification.
     */
    OptionSpec option(shortname, Map details, description) {
        OptionSpec.Builder builder
        if (shortname == '_') {
            builder = OptionSpec.builder("--$details.longOpt").description(description)
            details.remove('longOpt')
        } else {
            builder = OptionSpec.builder("-$shortname").description(description)
        }
        commons2picocli(shortname, details).each { key, value ->
            if (builder.hasProperty(key)) {
                builder[key] = value
            } else {
                builder.invokeMethod(key, value)
            }
        }
        return builder.build()
    }
//    * <pre>
//    *  - argName:        String
//    *  - longOpt:        String
//    *  - args:           int or String
//    *  - optionalArg:    boolean
//    *  - required:       boolean
//    *  - type:           Class
//    *  - valueSeparator: char
//    *  - convert:        Closure
//    *  - defaultValue:   String
//    * </pre>

    /** Commons-cli constant that specifies the number of argument values is infinite */
    private static final int COMMONS_CLI_UNLIMITED_VALUES = -2;

    static Map commons2picocli(shortname, Map m) {
//        if (m.args || m.optionalArg) { // if not boolean
//            m.type = List
//        }
        if (m.args && m.optionalArg) {
            m.arity = "0..${m.args}"
            m.remove('args')
            m.remove('optionalArg')
        }
        if (!m.defaultValue) {
            m.remove('defaultValue') // don't default the picocli model to empty string
        }
        def result = m.collectMany { k, v ->
            if (k == 'args' && v == '+') {
                [[arity: '1..*']]
            } else if (k == 'args') {
                v == COMMONS_CLI_UNLIMITED_VALUES ? [[arity: "*"]] : [[arity: "$v"]]
            } else if (k == 'optionalArg') {
                v ? [[arity: '0..1']] : [[arity: '1']]
            } else if (k == 'argName') {
                [[paramLabel: "<$v>"]]
            } else if (k == 'longOpt') {
                [[names: ["-$shortname", "-$v", "--$v"] as String[] ]]
            } else if (k == 'valueSeparator') {
                [[splitRegex: "$v"]]
            } else if (k == 'convert') {
                [[converters: [v] as ITypeConverter[] ]]
            } else {
                [[(k): v]]
            }
        }.sum() as Map
        result
    }
}

class OptionAccessor {
    ParseResult parseResult
    Map<String, TypedOption> savedTypeOptions

    OptionAccessor(ParseResult parseResult) {
        this.parseResult = parseResult
    }

    boolean hasOption(TypedOption typedOption) {
        parseResult.hasOption(typedOption.longOpt ?: typedOption.opt as String)
    }

    public <T> T defaultValue(String name) {
        Class<T> type = savedTypeOptions[name]?.type
        String value = savedTypeOptions[name]?.defaultValue() ? savedTypeOptions[name].defaultValue() : null
        return (T) value ? getTypedValue(type, name, value) : null
    }

    public <T> T getOptionValue(TypedOption<T> typedOption) {
        getOptionValue(typedOption, null)
    }

    public <T> T getOptionValue(TypedOption<T> typedOption, T defaultValue) {
        String optionName = (String) typedOption.longOpt ?: typedOption.opt
        parseResult.optionValue(optionName, defaultValue)
    }

    public <T> T getAt(TypedOption<T> typedOption) {
        getAt(typedOption, null)
    }

    public <T> T getAt(TypedOption<T> typedOption, T defaultValue) {
        String optionName = (String) typedOption.longOpt ?: typedOption.opt
        parseResult.optionValue(optionName, defaultValue)
    }

    private <T> T getTypedValue(Class<T> type, String optionName, String optionValue) {
        if (savedTypeOptions[optionName]?.cliOption?.arity?.min == 0) {
            return (T) parseResult.option(optionName)
        }
        def convert = savedTypeOptions[optionName]?.convert
        return getValue(type, optionValue, convert)
    }

    private <T> T getValue(Class<T> type, String optionValue, Closure convert) {
        if (!type) {
            return (T) optionValue
        }
        if (Closure.isAssignableFrom(type) && convert) {
            return (T) convert(optionValue)
        }
        if (type == Boolean || type == Boolean.TYPE) {
            return type.cast(Boolean.parseBoolean(optionValue))
        }
        StringGroovyMethods.asType(optionValue, (Class<T>) type)
    }

    def invokeMethod(String name, Object args) {
        if (name == 'getOptionValue') { name = 'optionValue'; args = [args[0], null].toArray() }
        return InvokerHelper.getMetaClass(parseResult).invokeMethod(parseResult, name, args)
    }

    def getProperty(String name) {
        if (parseResult.hasOption(name)) {
            def result = parseResult.optionValue(name, null)
            // if picocli has a strongly typed multi-value result, return it
            Class type = parseResult.option(name).type()
            if (type.isArray()) {
                return result ? result[0] : null
            } else if (Collection.class.isAssignableFrom(type)) {
                return (result as Collection)?.first()
            }
            return result
        }
        if (name.size() > 1 && name.endsWith('s')) { // user wants multi-value result
            def singularName = name[0..-2]
            if (parseResult.hasOption(singularName)) {
                // if picocli has a strongly typed multi-value result, return it
                Class type = parseResult.option(singularName).type()
                if (type.isArray() || Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
                    return parseResult.optionValue(singularName, null)
                }
                // otherwise, return the raw string values as a list
                return parseResult.rawOptionValues(singularName)
            }
        }
        false
    }

    List<String> arguments() {
        parseResult.hasPositional(0) ? parseResult.positional(0).rawStringValues() : []
    }
}
