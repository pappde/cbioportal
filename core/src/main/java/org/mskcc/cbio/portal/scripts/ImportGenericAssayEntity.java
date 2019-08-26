/*
* Copyright (c) 2019 The Hyve B.Vs.
*
* This library is distributed in the hope that it will be useful, but WITHOUT
* ANY WARRANTY, WITHOUT EVEN THE IMPLIED WARRANTY OF MERCHANTABILITY OR FITNESS
* FOR A PARTICULAR PURPOSE. The software and documentation provided hereunder
* is on an "as is" basis, and Memorial Sloan-Kettering Cancer Center has no
* obligations to provide maintenance, support, updates, enhancements or
* modifications. In no event shall Memorial Sloan-Kettering Cancer Center be
* liable to any party for direct, indirect, special, incidental or
* consequential damages, including lost profits, arising out of the use of this
* software and its documentation, even if Memorial Sloan-Kettering Cancer
* Center has been advised of the possibility of such damage.
*/

/*
* This file is part of cBioPortal.
*
* cBioPortal is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/**
* @author Pim van Nierop, pim@thehyve.nl
*/

package org.mskcc.cbio.portal.scripts;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;

import org.cbioportal.model.EntityType;
import org.cbioportal.model.GeneticEntity;
import org.cbioportal.model.meta.GenericAssayMeta;
import org.mskcc.cbio.portal.dao.DaoException;
import org.mskcc.cbio.portal.dao.DaoGenericAssay;
import org.mskcc.cbio.portal.dao.DaoGeneticEntity;
import org.mskcc.cbio.portal.dao.DaoTreatment;
import org.mskcc.cbio.portal.model.GeneticAlterationType;
import org.mskcc.cbio.portal.model.Treatment;
import org.mskcc.cbio.portal.util.ProgressMonitor;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

/**
* Note; Imports genetic entities from generic assay files. Has been written for treatment response data
* but is intended to be used for generic assay data in the future. The importer adds data to the treatment
* table. In later rounds of implementation of generic assay types this table should be renamed to fi. 
* `assay`. Also all remaining references to 'treatment(s)' should be removed from the importer code.  
*
* @author Pim van Nierop, pim@thehyve.nl
*/
public class ImportGenericAssayEntity extends ConsoleRunnable {

    public ImportGenericAssayEntity(String[] args) {
        super(args);
    }

    public ImportGenericAssayEntity(File dataFile, EntityType entityType, String columnNames, boolean updateInfo) {
        // fake the console arguments required by the ConsoleRunnable class
        super( new String[]{"--data", dataFile.getAbsolutePath(), "--entity-type", entityType.name(), "--column-names", columnNames, "--update-info", updateInfo?"1":"0"});
	}

	@Override
    public void run() {
        try {
            String progName = "ImportTreatments";
            String description = "Import treatment records from treatment response files.";
            
            OptionParser parser = new OptionParser();
            OptionSpec<String> data = parser.accepts("data", "Treatment data file")
            .withRequiredArg().ofType(String.class);

            // require entity type
            OptionSpec<String> entityType = parser.accepts("entity-type", "Entity type")
            .withRequiredArg().ofType(String.class);

            // don't require column names
            OptionSpec<String> columnNames = parser.accepts("column-names", "Column names")
            .withOptionalArg().ofType(String.class);
            
            OptionSet options = null;
            try {
                options = parser.parse(args);
            }
            catch (Exception ex) {
                throw new UsageException(
                progName, description, parser,
                ex.getMessage());
            }
            
            // if neither option was set then throw UsageException
            if (!options.has(data)) {
                throw new UsageException(
                progName, description, parser,
                "'data' argument required");
            }

            // if no entity type then throw UsageException
            if (!options.has(entityType)) {
                throw new UsageException(
                progName, description, parser,
                "'entityType' argument required");
            }
            
            // Check options
            boolean updateInfo = options.has("update-info");
            
            ProgressMonitor.setCurrentMessage("Adding new treatments to the database\n");
            startImport(options, data, entityType, columnNames, updateInfo);
            
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
    * Start import process for gene set file and supplementary file.
    *
    * @param updateInfo
    */
    public static void startImport(OptionSet options, OptionSpec<String> data, OptionSpec<String> geneticAlterationType, OptionSpec<String> columnNames, boolean updateInfo) throws Exception {
        if (options.hasArgument(data) && options.hasArgument(geneticAlterationType)) {
            File treatmentFile = new File(options.valueOf(data));
            GeneticAlterationType geneticAlterationTypeArg = GeneticAlterationType.valueOf(options.valueOf(geneticAlterationType));
            String columnNamesArg = options.valueOf(columnNames);
            importData(treatmentFile, geneticAlterationTypeArg, columnNamesArg);
        }
    }
    
    /**
    * Imports feature columns from treatment file.
    *
    * @param dataFile
    * @param updateInfo boolean that indicates wether additional fields
    * ("Description, Name, URL") of existing records should be overwritten
    * @throws Exception
    */
    public static void importData(File dataFile, GeneticAlterationType geneticAlterationType, String columnNames) throws Exception {
        
        ProgressMonitor.setCurrentMessage("Reading data from: " + dataFile.getCanonicalPath());
        
        // read gene set data file - note: this file does not contain headers
        FileReader reader = new FileReader(dataFile);
        BufferedReader buf = new BufferedReader(reader);
        String currentLine = buf.readLine();
        String[] headerNames = currentLine.split("\t");
        
        if (geneticAlterationType == GeneticAlterationType.TREATMENT) {
            // read treatment data
            int indexStableIdField = getStableIdIndex(headerNames);
            int indexNameField = getNameIndex(headerNames);
            int indexDescField = getDescIndex(headerNames);
            int indexUrlField = getTreatmentUrlIndex(headerNames);
            
            currentLine = buf.readLine();
            
            while (currentLine != null) {
                
                String[] parts = currentLine.split("\t");
                
                // assumed that fields contain: treat id, name, short name
                String treatmentStableId = parts[indexStableIdField];
                Treatment treatment = DaoTreatment.getTreatmentByStableId(treatmentStableId);
                
                // treatments are always updated to based on the current import;
                // also when present in db a new record is created.
                    
                // extract fields; replace optional fields with the Stable ID when not set
                String stableId = parts[indexStableIdField];
                String name = indexNameField == -1?stableId:parts[indexNameField];
                String desc = indexNameField == -1?stableId:parts[indexDescField];
                String url = indexNameField == -1?stableId:parts[indexUrlField];

                if (treatment == null) {
                
                    // create a new treatment and add to the database
                    Treatment newTreatment = new Treatment(stableId, name, desc, url);
                    ProgressMonitor.setCurrentMessage("Adding treatment: " + newTreatment.getStableId());
                    DaoTreatment.addTreatment(newTreatment);

                }
                // update the meta-information fields of the treatment
                else {

                    ProgressMonitor.setCurrentMessage("Updating treatment: " + treatment.getStableId());
                    treatment.setName(name);
                    treatment.setDescription(desc);
                    treatment.setRefLink(url);
                    DaoTreatment.updateTreatment(treatment);

                }

                currentLine = buf.readLine();
            }
        } else {
            // read generic assay data
            int indexStableIdField = getStableIdIndex(headerNames);

            currentLine = buf.readLine();
            
            while (currentLine != null) {
                
                String[] parts = currentLine.split("\t");
                
                // assumed that fields contain: treat id, name, short name
                String genericAssayMetaStableId = parts[indexStableIdField];
                GenericAssayMeta genericAssayMeta = DaoGenericAssay.getGenericAssayMetaByStableId(genericAssayMetaStableId);
                
                // generic assay meta are always updated to based on the current import;
                // also when present in db a new record is created.
                    
                // extract fields; replace optional fields with the Stable ID when not set
                String stableId = parts[indexStableIdField];
                HashMap<String, String> propertiesMap = new HashMap<>();
                if (columnNames != null) {
                    String[] columnNameList = columnNames.trim().split(",");
                    for (String columnName : columnNameList) {
                        int indexAdditionalField = getColIndexByName(headerNames, columnName);
                        if (indexAdditionalField != -1) {
                            propertiesMap.put(columnName, parts[indexAdditionalField]);
                        }
                    }
                }

                if (genericAssayMeta == null) {
                
                    // create a new generic assay meta and add to the database
                    GeneticEntity newGeneticEntity = new GeneticEntity(geneticAlterationType.name(), stableId);
                    ProgressMonitor.setCurrentMessage("Adding generic assay: " + newGeneticEntity.getStableId());
                    DaoGeneticEntity.addNewGeneticEntity(newGeneticEntity);
                    propertiesMap.forEach((k, v) -> {
                        try {
                            // ProgressMonitor.setCurrentMessage("key value: " + "k:" + k + "v:" + v);
                            DaoGenericAssay.setGenericEntityProperty(newGeneticEntity.getStableId(), k, v);
                        } catch (DaoException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    });

                }
                // update the meta-information fields of the generic assay
                else {

                    ProgressMonitor.setCurrentMessage("Updating generic assay: " + genericAssayMeta.getStableId());
                    DaoGenericAssay.deleteGenericEntityPropertiesByStableId(genericAssayMeta.getStableId());
                    propertiesMap.forEach((k, v) -> {
                        try {
                            // ProgressMonitor.setCurrentMessage("key value: " + "k:" + k + "v:" + v);
                            DaoGenericAssay.setGenericEntityProperty(genericAssayMeta.getStableId(), k, v);
                        } catch (DaoException e) {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                    });
                }

                currentLine = buf.readLine();
            }
        } 
        
        reader.close();
        
        ProgressMonitor.setCurrentMessage("Finished loading treatments.\n");
        
        return;
    }
    
    // returns index for entity_stable_id column
    private static int getStableIdIndex(String[] headers) {
        return getColIndexByName(headers, "entity_stable_id");
    }
    
    // returns index for name column
    private static  int getNameIndex(String[] headers) {
        return getColIndexByName(headers, ImportUtils.metaFieldTag+"name");
    }
    
    // returns index for description column
    private static  int getDescIndex(String[] headers) {
        return getColIndexByName(headers, ImportUtils.metaFieldTag+"description");
    }
    
    // returns index for treatment linkout url column
    private static  int getTreatmentUrlIndex(String[] headers) {
        return getColIndexByName(headers, ImportUtils.metaFieldTag+"url");
    }
    
    // helper function for finding the index of a column by name
    private static  int getColIndexByName(String[] headers, String colName) {
        for (int i=0; i<headers.length; i++) {
            if (headers[i].equalsIgnoreCase(colName)) {
                return i;
            }
        }
        return -1;
    }
    
    
    /**
     * usage:   --data <data_file.txt>
     *          --update-info [0:1]
     */
    public static void main(String[] args) {
        ConsoleRunnable runner = new ImportGenericAssayEntity(args);
        runner.runInConsole();
    }
}