/**
 * Copyright (C) 2014-2015 Renaud Richardet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sherlok;

import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;
import static org.apache.uima.ruta.engine.RutaEngine.PARAM_ADDITIONAL_ENGINES;
import static org.apache.uima.ruta.engine.RutaEngine.PARAM_DESCRIPTOR_PATHS;
import static org.apache.uima.ruta.engine.RutaEngine.PARAM_MAIN_SCRIPT;
import static org.apache.uima.ruta.engine.RutaEngine.PARAM_RESOURCE_PATHS;
import static org.apache.uima.ruta.engine.RutaEngine.PARAM_SCRIPT_PATHS;
import static org.apache.uima.ruta.engine.RutaEngine.SCRIPT_FILE_EXTENSION;
import static org.apache.uima.util.FileUtils.saveString2File;
import static org.sherlok.EngineOps.generateXmlDescriptor;
import static org.sherlok.utils.CheckThat.validateId;
import static org.sherlok.utils.Create.list;
import static org.sherlok.utils.Create.map;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;
import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.TypeSystem;
import org.apache.uima.cas.impl.FilteringTypeSystem;
import org.apache.uima.cas.impl.TypeSystemImpl;
import org.apache.uima.fit.component.NoOpAnnotator;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.TypeSystemDescriptionFactory;
import org.apache.uima.fit.pipeline.SimplePipeline;
import org.apache.uima.jcas.tcas.Annotation;
import org.apache.uima.json.JsonCasSerializer;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.metadata.TypeDescription;
import org.apache.uima.resource.metadata.TypeSystemDescription;
import org.apache.uima.ruta.engine.RutaEngine;
import org.apache.uima.ruta.ontologies.OntoActionExtension;
import org.apache.uima.util.CasPool;
import org.sherlok.RutaHelper.TypeDTO;
import org.sherlok.RutaHelper.TypeFeatureDTO;
import org.sherlok.config.ConfigVariableManager;
import org.sherlok.config.NoSuchVariableException;
import org.sherlok.config.ProcessConfigVariableException;
import org.sherlok.mappings.BundleDef.EngineDef;
import org.sherlok.mappings.PipelineDef;
import org.sherlok.mappings.SherlokException;
import org.slf4j.Logger;
import org.xml.sax.SAXException;

/**
 * Manages a UIMA pipeline (configuration, and then use/annotation), based on a
 * {@link PipelineDef}.<br>
 * Lifecycle:<br>
 * <ol>
 * <li>add deps on classpath (to have TypeSystems scannable)</li>
 * <li>create UimaPipeline with script, engines, annotations , ...</li>
 * <li>annotate texts...</li>
 * <li>close</li>
 * </ol>
 * 
 * 
 * TODO: the 'initScript' part was refactored. 'initEngines' part should be
 * refactored too.
 * 
 * @author renaud@apache.org
 * 
 */
public class UimaPipeline {
    private static Logger LOG = getLogger(UimaPipeline.class);

    private final PipelineDef pipelineDef;
    private final String language;

    private List<AnalysisEngineDescription> aeds = list();
    private AnalysisEngine[] aes = null;

    /** Keeps track of the {@link Type}s added in every Ruta script */
    private TypeSystemDescription tsd;
    private CasPool casPool;
    /** JSON serializer */
    private JsonCasSerializer jsonSerializer;

    /**
     * @param pipelineId
     * @param language
     *            , important e.g. for DKpro components.
     * @param engineDefs
     * @param scriptLines
     *            the Ruta script
     * @param annotationIncludes
     * @param annotationFilters
     */
    public UimaPipeline(PipelineDef pipelineDef, List<EngineDef> engineDefs)
            throws IOException, SherlokException, UIMAException {
        this.pipelineDef = pipelineDef;
        this.language = pipelineDef.getLanguage();

        this.tsd = reloadTSD();// needed since we have added new jars to the CP

        initScript(list(pipelineDef.getScriptLines()) /* a copy */, engineDefs);
        initEngines();
        casPool = initCasPool(tsd);
        jsonSerializer = filterAnnots(pipelineDef.getOutput()
                .getAnnotationIncludes(), pipelineDef.getOutput()
                .getAnnotationFilters(), casPool);

        // // ensures Ruta errors can be catched, at last
        // annotate("Some test text to check for Ruta script errors.");
    }

    static TypeSystemDescription reloadTSD() {
        try {
            TypeSystemDescriptionFactory.forceTypeDescriptorsScan();
            return TypeSystemDescriptionFactory.createTypeSystemDescription();
        } catch (ResourceInitializationException e) {
            throw new RuntimeException(e); // should not happen
        }
    }

    /**
     * @return a cas JSON serializer that filters the JSON output, either with
     *         includes or filters. If no includes/filter is provided, no
     *         filtering is performed (it just uses all the annotations from the
     *         {@link TypeSystem}).
     */
    static JsonCasSerializer filterAnnots(List<String> includes,
            List<String> filters, CasPool casPool) {

        CAS cas = casPool.getCas();
        TypeSystem ts = cas.getTypeSystem();
        TypeSystem filteredTs;
        // TODO first include, then filter!
        if (!includes.isEmpty()) {

            filteredTs = new FilteringTypeSystem();
            Iterator<Type> tit = ts.getTypeIterator();
            while (tit.hasNext()) {
                Type type = tit.next();

                boolean shouldInclude = false;
                for (String include : includes) {
                    if (include.endsWith(".*")
                            && type.getName().startsWith(
                                    include.substring(0, include.length() - 2))) {
                        shouldInclude = true;
                        break;
                    } else if (include.equals(type.getName())) {
                        shouldInclude = true;
                        break;
                    }
                }
                if (shouldInclude) {
                    LOG.trace("including type '{}'", type);
                    ((FilteringTypeSystem) filteredTs).includeType(type);
                }
            }

        } else if (!filters.isEmpty()) {

            filteredTs = new FilteringTypeSystem();
            Iterator<Type> tit = ts.getTypeIterator();
            while (tit.hasNext()) {
                Type type = tit.next();

                boolean shouldFilter = false;
                for (String filter : filters) {
                    if (filter.endsWith(".*")
                            && type.getName().startsWith(
                                    filter.substring(0, filter.length() - 2))) {
                        shouldFilter = true;
                        break;
                    } else if (filter.equals(type.getName())) {
                        shouldFilter = true;
                        break;
                    }
                }
                if (!shouldFilter) {
                    LOG.trace("including type '{}'", type);
                    ((FilteringTypeSystem) filteredTs).includeType(type);
                } else {
                    LOG.trace("filtering type '{}'", type);
                }
            }

        } else { // no filtering or includes --> use full ts
            LOG.trace("including all type");
            filteredTs = ts;
        }
        casPool.releaseCas(cas);

        // initialize JSON writer with filter
        return new JsonCasSerializer().setFilterTypes(
                (TypeSystemImpl) filteredTs).setPrettyPrint(true);
    }

    static final Map<String, String> CHAR_MAPPING = map();
    static {
        CHAR_MAPPING.put("LPAREN", "(");
        CHAR_MAPPING.put("RPAREN", ")");
        CHAR_MAPPING.put("STAR", "*");
        CHAR_MAPPING.put("PLUS", "+");
        CHAR_MAPPING.put("SEMI", ";");
        CHAR_MAPPING.put("LCURLY", "{");
        CHAR_MAPPING.put("RCURLY", "}");
    }

    private void initEngines() throws UIMAException, SherlokException {
        // redirect stdout to catch Ruta script errors
        ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
        ByteArrayOutputStream baosErr = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        System.setOut(new PrintStream(baosOut));
        System.setErr(new PrintStream(baosErr));

        try {
            // initialize Engines
            aes = createEngines(aeds.toArray(new AnalysisEngineDescription[aeds
                    .size()]));
        } finally { // so that we restore Sysout in any case

            // catching Ruta script outputs (these contain errors)
            String maybeOut = baosOut.toString();
            System.setOut(origOut); // restore
            String maybeErr = baosErr.toString();
            System.setErr(origErr); // restore

            if (maybeErr.startsWith("Adding annotator")) {
                maybeErr = "";// fix for StanfordNLP output FIXME
            } else if (maybeErr
                    .contains("Couldn't open cc.mallet.util.MalletLogger")) {
                maybeErr = "";// fix for Mallet FIXME
            }

            if (maybeOut.length() > 0)
                LOG.info(maybeOut);
            if (maybeErr.length() > 0)
                LOG.error(maybeErr);

            if (maybeErr.length() > 0) {
                LOG.info("Ruta script error" + maybeErr);
                throw new SherlokException("Ruta script error: " + maybeErr)
                        .setObject(this.pipelineDef.toString());
            }
            for (String line : maybeOut.split("\n")) {
                if (line.startsWith("Error in line")) {
                    // translate error messages
                    for (Entry<String, String> e : CHAR_MAPPING.entrySet()) {
                        line = line.replaceAll(e.getKey(), "'" + e.getValue()
                                + "'");
                    }
                    throw new SherlokException("Ruta script error on line "
                            + line).setObject(this.pipelineDef.toString());
                }
            }
        }
    }

    static CasPool initCasPool(TypeSystemDescription tsd)
            throws ResourceInitializationException {

        // for (TypeDescription td : tsd.getTypes())
        // LOG.debug("type: {}", td.getName());

        AnalysisEngine noOpEngine = AnalysisEngineFactory.createEngine(
                NoOpAnnotator.class, tsd);
        return new CasPool(10, noOpEngine);
    }

    private static AnalysisEngine[] createEngines(
            AnalysisEngineDescription... descs) throws UIMAException {
        AnalysisEngine[] engines = new AnalysisEngine[descs.length];
        for (int i = 0; i < engines.length; ++i) {
            if (descs[i].isPrimitive()) {
                engines[i] = AnalysisEngineFactory.createEngine(descs[i]);
            } else {
                engines[i] = AnalysisEngineFactory.createEngine(descs[i]);
            }
            // FIXME both if and else bodies are equivalent. Should something
            // special be done or should the if statement be removed?
            // NB: testing sentence, linnaeus and regions: all involved
            // descriptions are primitives.
        }
        return engines;
    }

    public interface Annotate {
        public Object annotate(final CAS cas, final AnalysisEngine[] aes)
                throws AnalysisEngineProcessException;
    }

    /**
     * @param annotate
     *            visitor pattern
     * @return a payload, defined by 'annotate'
     */
    public Object annotate(Annotate annotate) throws UIMAException,
            SAXException, SherlokException {

        CAS cas = null;
        try {
            // TODO how long to wait?
            cas = casPool.getCas(0);// cas.reset done by casPool
            return annotate.annotate(cas, aes);
        } finally {
            casPool.releaseCas(cas);
        }
    }

    /**
     * @param text
     *            the text to annotate
     */
    public String annotate(String text) throws UIMAException, SherlokException {

        CAS cas = null;
        try {
            // TODO how long to wait?
            cas = casPool.getCas(0);// cas.reset done by casPool
            // for (TypeDescription td : tsd.getTypes())
            // LOG.debug("type: {} <<<< {}", td.getName(),
            // td.getSupertypeName());

            cas.setDocumentText(text);
            cas.setDocumentLanguage(language);

            LOG.trace("annotating: " + text);
            SimplePipeline.runPipeline(cas, aes);

            if (LOG.isTraceEnabled()) {
                FSIterator<Annotation> it = cas.getJCas().getAnnotationIndex()
                        .iterator();
                while (it.hasNext()) {
                    Annotation a = it.next();
                    StringBuffer sb = new StringBuffer();
                    a.prettyPrint(2, 2, sb, false);
                    LOG.trace("'{}'\t{}", a.getCoveredText(), sb.toString()
                            .replaceAll("[\r\n] *", "\t"));
                }
            }

            StringWriter sw = new StringWriter();
            jsonSerializer.serialize(cas, sw);

            String json = sw.toString();
            // rename JSON field, for readability
            json = json.replaceFirst("@cas_feature_structures", "annotations");
            return json;

        } catch (AnalysisEngineProcessException aepe) {
            Throwable cause = aepe.getCause();
            if (cause instanceof IllegalArgumentException) {
                throw new SherlokException("Failed to annotate " + text,
                        this.toString()).setDetails(cause.getMessage());
            } else {
                throw aepe;
            }
        } catch (IOException io) {
            throw new SherlokException("Failed to annotate " + text,
                    this.toString()).setDetails(io.getMessage());
        } finally {
            casPool.releaseCas(cas);
        }
    }

    public void close() {
        for (AnalysisEngine engine : aes) {
            engine.destroy();
        }
    }

    public PipelineDef getPipelineDef() {
        return pipelineDef;
    }

    @Override
    public String toString() {
        return pipelineDef.toString();
    }

    private void initScript(List<String> scriptLines, List<EngineDef> engineDefs)
            throws ResourceInitializationException, IOException,
            SherlokException {

        // Handle variables in script
        try {
            scriptLines = ConfigVariableManager.processConfigVariables(
                    scriptLines, pipelineDef);
        } catch (NoSuchVariableException | ProcessConfigVariableException e) {
            throw new SherlokException(e.getMessage()).setObject(pipelineDef
                    .toString());
        }

        // load engines
        List<String> engineDescriptions = list();
        for (int i = 0; i < scriptLines.size(); i++) {

            String scriptLine = scriptLines.get(i);

            if (scriptLine.startsWith("ENGINE")) {
                // find the corresponding engine description
                String engineId = extractEngineId(scriptLine);

                // create ae and write xml descriptor
                String engineDescription = generateXmlDescriptor(engineId,
                        engineDefs);
                engineDescriptions.add(engineDescription);

                // update script line
                scriptLines.set(i, "Document{-> EXEC(" + engineDescription
                        + ")}; // " + scriptLine);
            }
        }

        // ensure PACKAGE is present in script
        String script = StringUtils.join(scriptLines, "\n").trim();
        String nameSpace;
        if (!script.startsWith("PACKAGE")) {
            nameSpace = "org.sherlok.ruta";
            script = "PACKAGE " + nameSpace + ";\n" + script;
        } else {
            nameSpace = script.substring(0, script.indexOf(";"))
                    .replaceFirst("PACKAGE", "").trim();
        }

        // add engines to script
        if (!engineDescriptions.isEmpty()) {
            String scriptTmp = script.split("\n")[0] + "\n\n";
            String rest = "\n" + script.substring(script.indexOf("\n"));
            for (String engineDescription : engineDescriptions) {
                scriptTmp += "ENGINE " + engineDescription + ";\n";
            }
            script = scriptTmp + rest;
        }

        // add types
        for (TypeDTO t : RutaHelper.parseDeclaredTypes(script)) {
            // fix type and supertype names (add namespace)
            String typeName = nameSpace + "." + t.typeName;
            String supertypeName = t.supertypeName;
            if (supertypeName.indexOf('.') == -1) {
                supertypeName = nameSpace + "." + supertypeName;
            }
            LOG.trace("adding type {}::{}", typeName, supertypeName);

            TypeDescription typeD = tsd.addType(typeName, t.description,
                    supertypeName);
            for (TypeFeatureDTO f : t.getTypeFeatures()) {
                LOG.trace("  adding feat {}::{}", f.featureName,
                        f.getRangeTypeNameCleaned());
                typeD.addFeature(f.featureName, f.description,
                        f.getRangeTypeNameCleaned());
            }
        }

        // write Ruta script to tmp file
        // ruta does not like dots
        String scriptName = pipelineDef.getId().replace(".", "_");
        File scriptFile = new File(FileBased.PIPELINE_CACHE_PATH + scriptName
                + SCRIPT_FILE_EXTENSION);
        scriptFile.getParentFile().mkdirs();
        saveString2File(script, scriptFile);

        String[] extensions = { OntoActionExtension.class.getName() };

        aeds.add(createEngineDescription(RutaEngine.class, //
                PARAM_SCRIPT_PATHS, scriptFile.getParent(), //
                PARAM_RESOURCE_PATHS, FileBased.RUTA_RESOURCES_PATH, //
                PARAM_DESCRIPTOR_PATHS, FileBased.ENGINE_CACHE_PATH,//
                PARAM_ADDITIONAL_ENGINES, engineDescriptions,//
                RutaEngine.PARAM_ADDITIONAL_EXTENSIONS, extensions,//
                PARAM_MAIN_SCRIPT, scriptName));
    }

    /** Extract the engine ID from a "ENGINE $id;" script line */
    private static String extractEngineId(String scriptLine)
            throws SherlokException {
        String pengineId = scriptLine.trim().substring("ENGINE".length())
                .trim().replaceAll(";", "");
        validateId(pengineId, "ENGINE id in '" + pengineId + "'");
        return pengineId;
    }
}
