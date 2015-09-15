package org.transmart.tm2cbio

/**
 * Created by j.hudecek on 20-3-2015.
 */
class ClinicalTranslator extends AbstractTranslator {

    private Map concept2col
    //column names to replace
    private Map toReplace
    //columns to run conversion on
    private Map toConvert

    private static final String termSeparator = '/'

    private List<String> exportColumns

    private ClinicalConfig specificConfig

    private String forConfigNumber

    public void createMetaFile(Config c) {
        def metaclinical = new File(c.target_path + "/meta_clinical.txt");
        metaclinical.write("""cancer_study_identifier: ${c.study_id}
genetic_alteration_type: CLINICAL
datatype: FREE-FORM
stable_id: ${c.study_id}_clinical
profile_description: Clinical data for ${c.patient_count} patients from tranSMART.
show_profile_in_analysis_tab: true
profile_name: Clinical
""")
    }

    public SetList<String> writeDataFile(Config c, SetList<String> patients)
    {
        new File(c.target_path + "/data_clinical.txt").withWriter {out ->
            writeMeta(out, concept2col)
            patients = writeData(out, toReplace, toConvert, patients)
        }
        println("Created data file $forConfigNumber'"+c.target_path + "/data_clinical.txt'")
        return patients
    }

    public ClinicalTranslator(Config c, int config_number)
    {
        specificConfig = c.specific_configs["clinical"][config_number]
        configNumber = config_number
        concept2col = createConceptToColumnMapping(c)
        // figure out on which column index are the special columns that need replacing/converting
        toReplace = [:]
        toConvert = [:]
        specificConfig.special_attributes.each({
            def replaceName = "mapping_"+it+"_replace"
            def convertName = "mapping_"+it+"_convert"
            def pathName = "mapping_"+it+"_path"
            if (specificConfig.hasProperty(replaceName) && specificConfig.@"$replaceName" != null)
                toReplace.put(exportColumns.findIndexOf {it==specificConfig.translateConcept(specificConfig.@"$pathName")}, replaceName)
            if (specificConfig.hasProperty(convertName) && specificConfig.@"$convertName" != null)
                toConvert.put(exportColumns.findIndexOf {it==specificConfig.translateConcept(specificConfig.@"$pathName")}, convertName)
        })
        if (config_number > 0)
            forConfigNumber = " for config number $config_number"
    }

    @Override
    String getCaseListName() {
        'clinical'
    }

    private String applyRegexes(String input, String regexesInConfig) {
        String with_name = regexesInConfig+"_with"
        specificConfig.@"$regexesInConfig".eachWithIndex({ regex,i -> input = input.replace(regex, specificConfig.@"$with_name"[i])})
        return input;
    }

    private String applyConversion(String input, String converter) {
        def methodName = specificConfig.@"$converter"
        Converter."$methodName"(input)
    }

    private SetList<String> writeData(out, Map toReplace, Map toConvert, SetList<String> patients) {
        new File(specificConfig.file_path).eachLine {line, lineNumber ->
            if (lineNumber == 1)
                return;
            String[] rawFields = line.split('\t')
            //patient id will be sample id as well, we need it there twice, trim trailing spaces
            rawFields[0] = rawFields[0].trim()
            if (rawFields[0].indexOf(' ') != -1)
                throw new IllegalArgumentException("Patient or sample IDs$forConfigNumber can't contain spaces!")
            if (rawFields[0].indexOf(',') != -1)
                throw new IllegalArgumentException("Patient or sample IDs$forConfigNumber can't contain commas!")
            def fields = [rawFields[0]]
            fields.addAll(rawFields)
            if (patients.contains(fields[0])) {
                println("WARNING: duplicate ID in clinical data$forConfigNumber '${fields[0]}', ignoring data on line $lineNumber")
                return
            }
            //store patient's ID (first column) for case list
            patients.push(fields[0])
            patientsForThisDataType.push(fields[0])
            toReplace.each {
                fields[it.key] = applyRegexes(fields[it.key], it.value)
            }
            toConvert.each {
                try {
                    fields[it.key] = applyConversion(fields[it.key], it.value)
                }
                catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Invalid format for conversion$forConfigNumber '" + it.value + "': " + fields[it.key] + " on line " + lineNumber + " on column number " + it.key, ex)
                }
            }
            out.println(fields.join('\t'))
        }
        return patients
    }

    private void writeMeta(Writer out, Map concept2col) {
        //write meta information
        out.println("#" + exportColumns.collect({
            concept2col[it]
        }).join('\t'));
        out.println("#" + exportColumns.join('\t'));
        out.println("#" + exportColumns.collect({
            getTypeForConcept(it)
        }).join('\t'));
        out.println(exportColumns.collect({
            "SAMPLE" //for SAMPLE/PATIENT type
        }).join('\t'))
        out.println(exportColumns.collect({
            "5" //for priority
        }).join('\t'))
        out.println(exportColumns.collect({
            concept2col[it]
        }).join('\t'))
    }

    private String getTypeForConcept(String it) {
        if (specificConfig.types.containsKey(it))
            return specificConfig.types[it]
        else
            "STRING"
    }

    private String getLeaf(String concept) {
        String pattern = '.*'+termSeparator;
        concept.replaceAll(~pattern, '')
    }


    private Map createConceptToColumnMapping(Config c) {
        String firstRow;
        println("Reading data file '"+specificConfig.file_path+"'")
        new File(specificConfig.file_path).withReader { firstRow = it.readLine() } ;
        firstRow = applyRegexes(firstRow, "mapping_concept_to_column_name_replace")
        firstRow = specificConfig.translateConcept(firstRow)

        //prepend PATIENT_ID and SAMPLE ID
        exportColumns = ['PATIENT_ID']
        exportColumns.addAll(firstRow.split('\t'))
        exportColumns[1] = 'SAMPLE_ID'
        //get a clean list of concepts (must have a \)
        String[] concepts = exportColumns.dropWhile {it.indexOf(termSeparator) == -1}
        //map of concept from transmart and its column in cbioportal, initiated to the leaf name
        def concept2col = concepts.collectEntries {[it, getLeaf(it)]}
        HashMap<String, List<String>> leaf2concept = new HashMap<String, List<String>>();
        //get a list of collisions - different concepts with the same leaf name
        concepts.each {
            String leaf = getLeaf(it);
            if (!leaf2concept.containsKey(leaf))
                leaf2concept[leaf] = [];
            leaf2concept[leaf].push(it)
        }

        //prepend unique parts of the path to the leaf name to avoid the collision
        leaf2concept.each {
            List<String> conceptsInCollision = it.value;
            if (conceptsInCollision.size() > 1) {
                //get a list of terms per concept
                def termsPerConcept = conceptsInCollision*.split(termSeparator).toList()
                //concepts can have a different number of terms, iterate over the longest one
                int longestPathLength = termsPerConcept.max({it.size()}).size()
                def longestPath = termsPerConcept.find { it.size() == longestPathLength }
                List<Integer> toskip = [];

                for (int i = 0; i < longestPathLength; i++) {
                    //remove all terms which are the same in all the concepts
                    def term = longestPath[i];
                    if (termsPerConcept.every({ it.size() <= i || it[i] == term})) {
                        //all concepts have the same term on this position (or they are shorter), skip this term
                        toskip.push(i)
                    }
                }
                //set the concatenated terms as the new column label for the concept
                termsPerConcept.eachWithIndex({terms, i ->
                    def uniqueTerms = []
                    for (int j=0;j<terms.size()-1;j++)
                        if (!toskip.contains(j))
                            uniqueTerms.push(terms[j])
                    //always keep the leaf
                    uniqueTerms.push(terms.last())
                    concept2col[conceptsInCollision[i]] = uniqueTerms.join('_')
                })
            }
        }
        //add non concept columns without any mapping
        exportColumns.takeWhile {it.indexOf(termSeparator) == -1}.each {
            concept2col.put(it, it)
        }

        //rename standard (AGE, SEX, ...) columns
        specificConfig.special_attributes.each({
            def pathName = "mapping_"+it+"_path"
            if (specificConfig.@"$pathName" != null)
                concept2col[specificConfig.translateConcept(specificConfig.@"$pathName")] = it
        })

        //check uniqueness of columns just in case...
        def uniqueCols = concept2col.values().unique(false)
        if (uniqueCols.size() != concept2col.values().size()) {
            //restore original ordering of columns for the error message
            throw new IllegalArgumentException("Columns are not unique$forConfigNumber: "
                    + exportColumns.collect({ concept2col[it] }).join(' '))
        }
        concept2col
    }
}