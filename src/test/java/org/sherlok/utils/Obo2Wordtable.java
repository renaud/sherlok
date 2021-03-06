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
package org.sherlok.utils;

import static org.apache.commons.io.FilenameUtils.removeExtension;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.uima.ruta.tag.obo.OBOOntology;
import org.apache.uima.ruta.tag.obo.OboFormatException;
import org.apache.uima.ruta.tag.obo.OntologyTerm;
import org.apache.uima.ruta.tag.obo.RoboExpander;
import org.apache.uima.ruta.tag.obo.Synonym;

/**
 * Coverts three HBP OBO ontologies into Ruta WORDTABLES to be consumed by
 * NeuroNER.
 * 
 * @see SyncNeuroner
 * @author richarde
 */
public class Obo2Wordtable {

    private static final String DIR = "/Users/richarde/git2/neuroNER/resources/bluima/neuroner";

    public static void main(String[] args) throws IOException,
            OboFormatException {

        // OBO
        for (String oboFile : new String[] {//
        "GeneNames.obo",//
                "hbp_brainregions_aba-syn.obo",//
                "hbp_developmental_ontology.obo",//

                "hbp_morphology_ontology.obo",//
                "hbp_neurotransmitter_ontology.obo",//
                "regions.obo" }) {

            OBOOntology obo = new OBOOntology();
            obo.read(new File(DIR, oboFile));

            process(obo, oboFile);
        }

        // ROBO
        for (String roboFile : new String[] {
                "hbp_electrophysiology-triggers_ontology.robo",//
                "hbp_electrophysiology_ontology.robo",//
                "hbp_layer_ontology.robo" }) {
            OBOOntology obo = RoboExpander.expand(new File(DIR, roboFile));
            process(obo, removeExtension(roboFile));
        }
    }

    @SuppressWarnings("resource")
    private static void process(OBOOntology obo, String oboFile)
            throws IOException {

        FileWriter wordtableWriter = new FileWriter(new File(DIR,
                removeExtension(oboFile) + ".csv"));

        // TextFileWriter wordtableWriter = new TextFileWriter(new File(
        // WORDTABLE_OUTPUT, oboFile + ".csv"));
        // TODO can add comment at top with 'generated by ...'?

        Set<String> uniqueIds = Create.set();
        for (Entry<String, OntologyTerm> termE : obo.terms.entrySet()) {

            OntologyTerm term = termE.getValue();
            String id = term.getId();
            if (uniqueIds.contains(id)) {
                throw new RuntimeException("duplicate id: " + id);
            }
            uniqueIds.add(id);

            String name = term.getName();
            wordtableWriter.write(name + ";" + id + "\n");

            for (Synonym syn : term.getSynonyms()) {
                wordtableWriter.write(syn.getSyn() + ";" + id + "\n");
            }
        }

        wordtableWriter.close();
    }
}