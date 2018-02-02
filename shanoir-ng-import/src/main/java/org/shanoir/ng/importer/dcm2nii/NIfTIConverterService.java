package org.shanoir.ng.importer.dcm2nii;

import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SystemUtils;
import org.shanoir.ng.importer.model.Dataset;
import org.shanoir.ng.importer.model.DatasetFile;
import org.shanoir.ng.importer.model.ExpressionFormat;
import org.shanoir.ng.importer.model.Image;
import org.shanoir.ng.importer.model.Patient;
import org.shanoir.ng.importer.model.Serie;
import org.shanoir.ng.importer.model.Study;
import org.shanoir.ng.shared.exception.ErrorModel;
import org.shanoir.ng.shared.exception.RestServiceException;
import org.shanoir.ng.utils.DiffusionUtil;
import org.shanoir.ng.utils.ImportUtils;
import org.shanoir.ng.utils.ShanoirExec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The NIfTIConverter does the actual conversion of dcm to nii files.
 * To use the converter the dcm files have to be put in separate folders.
 * 
 * 1) all images for one serie are moved into /SERIES/{seriesID} and
 * 2) all images are concerning the acquisitionNumber, echoNumbers and the
 * imageOrientationPatient informations moved into /dataset{index} folders.
 * 
 * Inside each dataset folder the nii conversion is called.
 * 
 * @author mkain
 *
 */
@Service
public class NIfTIConverterService {

	private static final Logger LOG = LoggerFactory.getLogger(NIfTIConverterService.class);

	private static final String DOUBLE_EQUAL = "==";

	private static final String SEMI_COLON = ";";
	
	private static final String SERIES = "SERIES";
	
	private static final String DATASET = "dataset";
	
	@Autowired
	private NIfTIConverterRepository niftiConverterRepository;
	
	@Autowired
	private ShanoirExec shanoirExec;
	
	@Value("${shanoir.import.series.donotseparatedatasetsinserie}")
	private String doNotSeparateDatasetsInSerie;

	@Value("${shanoir.conversion.dcm2nii.converters.convertwithclidcm}")
	private String convertWithClidcm;

	@Value("${shanoir.conversion.dcm2nii.converters.convertas4d}")
	private String convertAs4D;
	
	@Value("${shanoir.conversion.dcm2nii.converters.path.linux}")
	private String convertersPathLinux;
	
	@Value("${shanoir.conversion.dcm2nii.converters.path.windows}")
	private String convertersPathWindows;
	
	@Value("${shanoir.conversion.dcm2nii.converters.clidcm.path.linux}")
	private String clidcmPathLinux;
	
	@Value("${shanoir.conversion.dcm2nii.converters.clidcm.path.windows}")
	private String clidcmPathWindows;
	
	/** Logs of the conversion. */
	private String conversionLogs;
	
	/** Output files mapped by series UID. */
	private HashMap<String, List<String>> outputFiles = new HashMap<String, List<String>>();
	
	public void prepareAndRunConversion(Patient patient, File unzipFolderFile, Long converterId) throws RestServiceException {
		File seriesFolderFile = new File(unzipFolderFile.getAbsolutePath() + File.separator + SERIES);
		if(!seriesFolderFile.exists()) {
			seriesFolderFile.mkdirs();
		} else {
			throw new RestServiceException(
					new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Error while creating series folder: folder already exists.", null));
		}

		conversionLogs = "";
		
		Set<File> pathsSet = new HashSet<File>(2000);
		List<Study> studies = patient.getStudies();
		for (Iterator studiesIt = studies.iterator(); studiesIt.hasNext();) {
			Study study = (Study) studiesIt.next();
			List<Serie> series = study.getSeries();
			float seriesCounter = 0;
			int numberOfSeries = series.size();
			for (Iterator seriesIt = series.iterator(); seriesIt.hasNext();) {
				Serie serie = (Serie) seriesIt.next();
				File serieIDFolderFile = createSerieIDFolder(seriesFolderFile, serie);
				boolean serieIdentifiedForNotSeparating;
				try {
					serieIdentifiedForNotSeparating = checkSerieForPropertiesString(serie, doNotSeparateDatasetsInSerie);
					// if the serie is not one of the series, that should not be separated, please separate the series,
					// otherwise just do not separate the series and keep all images for one nii conversion
					if (!serieIdentifiedForNotSeparating) {
						separateDatasetsInSerie(serieIDFolderFile, serie);
					}
					boolean propFileConversionError = false;
					runConversion(serieIDFolderFile, serie, seriesCounter, numberOfSeries, propFileConversionError, converterId);
					++seriesCounter;
				} catch (NoSuchFieldException | SecurityException e) {
					LOG.error(e.getMessage());
				}
			}
		}	
	}
	
	/**
	 * This method does the actual conversion on calling the converter, either on the level
	 * of the serie folder or on the level of each dataset folder inside the serie folder.
	 * Attention: this code does not take care of spectroscopy aspects. There should be a
	 * separate import code for this data.
	 * 
	 * In Shanoir old implementation the outputFiles is never used afterwards in the import
	 * (only for cleaning after the import has finished). In MrDatasetAcquisitionHome in the
	 * method getDicomFiles for each dataset0,1,.. folder all files are listed, when they are
	 * in listed in the serie meta-data they are considered as dicom, all others as nii files.
	 * It would be much better to add this infos to the json from here and just use them after?
	 * 
	 * @todo the conversion progress needs to be send to the frontend to be displayed
	 * 
	 * @param serieIDFolderFile
	 * @param serie
	 * @param seriesCounter
	 * @param numberOfSeries
	 * @throws SecurityException 
	 * @throws NoSuchFieldException 
	 */
	private void runConversion(File serieIDFolderFile, final Serie serie, float seriesCounter, int numberOfSeries, boolean propFileConversionError, Long converterId) throws NoSuchFieldException, SecurityException {
		// add all the directories (names = dataset0, dataset1, etc.)
		List<File> datasetDirectories = ImportUtils.listFolders(serieIDFolderFile);
		if (datasetDirectories == null || (datasetDirectories != null && datasetDirectories.isEmpty())) {
			datasetDirectories = new ArrayList<File>();
			datasetDirectories.add(serieIDFolderFile);
		}

		LOG.debug("convertToNifti : create nifti files for the serie : " + serieIDFolderFile.getAbsolutePath());
		//float datasetsCounter = 0;
		for (final File directory : datasetDirectories) {
			// float progress = (seriesCounter + (++datasetsCounter / datasetDirectories.size())) / numberOfSeries * 100;
			if (directory.isDirectory()) {
				// search for the existing files in the destination folder
				final List<File> existingFiles = Arrays.asList(directory.listFiles());
				LOG.info("convertToNifti : create nifti files for the dataset : " + directory.getName());
				if (conversionLogs != null && !"".equals(conversionLogs)) {
					conversionLogs += "\n";
				} else {
					conversionLogs = "";
				}
				boolean isConvertAs4D = checkSerieForPropertiesString(serie, convertAs4D);
				boolean isConvertWithClidcm = checkSerieForPropertiesString(serie, convertWithClidcm);
				NIfTIConverter converter;
				if(isConvertWithClidcm) {
					 converter = findById(3L);					
				} else {
					converter = findById(converterId);
				}
				convertToNiftiExec(converter, directory.getPath(), directory.getPath(), isConvertAs4D);
				LOG.info("conversionLogs : " + conversionLogs);

				// If one of the output files is a prop file, there has been an error
				if (outputFiles.get(serieIDFolderFile.getName()) != null) {
					List<File> niiFiles = diff(existingFiles, directory.getPath());
					if (!containsPropFile(niiFiles)) {
						for (File niiFile : niiFiles) {
							outputFiles.get(serieIDFolderFile.getName()).add(niiFile.getAbsolutePath());
							LOG.info("Path niiFile : " + niiFile.getAbsolutePath());
						}
					} else {
						propFileConversionError = true;
					}
				} else {
					List<String> niiPathList = new ArrayList<String>();
					if (!containsPropFile(diff(existingFiles, directory.getPath()))) {
						for (File niiFile : diff(existingFiles, directory.getPath())) {
							niiPathList.add(niiFile.getAbsolutePath());
							LOG.info("Path niiFile : " + niiFile.getAbsolutePath());
						}
						outputFiles.put(serieIDFolderFile.getName(), niiPathList);
					} else {
						propFileConversionError = true;
					}
				}
				// delete the unused files
				removeUnusedFiles();
			}

		}
	}
	
	public NIfTIConverter findById(Long id) {
		return niftiConverterRepository.findOne(id);
	}
	
	/**
	 * Execute the Nifti conversion
	 *
	 * @param converter
	 * @param input folder
	 * @param output folder
	 * @param boolean is a 4D volume
	 * @param boolean is convert to clidcm
	 *
	 */
	private void convertToNiftiExec(NIfTIConverter converter, String inputFolder, String outputFolder, boolean is4D) {
		String converterPath = null;
		if (SystemUtils.IS_OS_WINDOWS) {
			converterPath = convertersPathWindows + converter.getName();
		} else if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
			converterPath = convertersPathLinux + converter.getName();
		}
		// Mcverter
		if (converter != null && converter.isMcverter()) {
			is4D = true;
			conversionLogs += shanoirExec.mcverterExec(inputFolder, converterPath, outputFolder, is4D);
		// Clidcm
		} else if (converter != null && converter.isClidcm()) {
			// Must catch exception to try the creatbvecandbval even if there was an error
			// in clidcm conversion
			if (SystemUtils.IS_OS_WINDOWS) {
				converterPath = clidcmPathWindows;
			} else if (SystemUtils.IS_OS_LINUX || SystemUtils.IS_OS_MAC) {
				converterPath = clidcmPathLinux;
			}
			try {
				conversionLogs += shanoirExec.clidcmExec(inputFolder, converterPath, outputFolder);
			} catch (Exception e) {
				LOG.debug("Error converting with clidcm outputfolder : " + outputFolder + " - is4D :" + is4D + " - ", e);
			}
			/*
			 * Some '.prop' files may have been created. We want to convert the mas '.bvec'
			 * and '.bval' files because this is the type of files normally created by
			 * dcm2nii .
			 */
			createBvecAndBval(outputFolder);
		// Dicom2Nifti
		} else if (converter != null && converter.isDicom2Nifti()) {
			is4D = true;
			conversionLogs += shanoirExec.dicom2niftiExec(inputFolder, converterPath, outputFolder, is4D);
		// dcm2nii
		} else {
			is4D = true;
			conversionLogs += shanoirExec.dcm2niiExec(inputFolder, converterPath, outputFolder, is4D);
		}
	}
	
	/**
	 * Search for a '.prop' file. If found, then creates a '.bvec' and a '.bval'
	 * file from the '.prop' file.
	 *
	 * @param path
	 */
	private List<File> createBvecAndBval(final String path) {
		LOG.debug("createBvecAndBval : Begin, params : path=" + path);
		List<File> bvecAndBval = new ArrayList<File>();
		final FilenameFilter filter = new FilenameFilter() {
			@Override
			public boolean accept(final File dir, final String name) {
				final boolean accept = FilenameUtils.isExtension(name, new String[] { "prop", "PROP" });
				return accept;
			}
		};
		final String[] propFiles = new File(path).list(filter);
		if (propFiles != null && propFiles.length != 0) {
			for (final String propFile : propFiles) {
				LOG.debug("createBvecAndBval : '.prop' file found : " + propFile);
				final List<File> thisList = DiffusionUtil.propToBvecBval(new File(path, propFile), new File(path));
				LOG.debug("createBvecAndBval : bvec and bvals created : " + thisList);
				bvecAndBval.addAll(thisList);
			}
		}
		if(bvecAndBval.isEmpty()) conversionLogs += "There was an error creating bvec and bval. DiffusionGradientOrientation and/or B0 values may be missing in DICOM file.";
		LOG.debug("createBvecAndBval : end");
		return bvecAndBval;
	}
	
	/**
	 * Remove unused files that are created during the conversion process.
	 */
	private void removeUnusedFiles() {
		final List<File> toBeRemovedList = new ArrayList<File>();
		for (final List<String> listPath : outputFiles.values()) {
			for (final String path : listPath) {
				File file = new File(path);
				if (file.getName().startsWith("o") || file.getName().startsWith("x")) {
					toBeRemovedList.add(file);
				}
			}
		}
		for (final File toBeRemovedFile : toBeRemovedList) {
			// TODO : ne marche pas
			outputFiles.remove(toBeRemovedFile);
			boolean success = toBeRemovedFile.delete();
			if (!success) {
				LOG.error("removeUnusedFiles : error while deleting " + toBeRemovedFile);
			}
		}
	}

	/**
	 * This method receives a serie object and a String from the properties
	 * and checks if the tag exists with a specific value.
	 * @throws SecurityException 
	 * @throws NoSuchFieldException 
	 */
	private boolean checkSerieForPropertiesString(final Serie serie, final String propertiesString) throws NoSuchFieldException, SecurityException {
		final String[] itemArray = propertiesString.split(SEMI_COLON);
		for (final String item : itemArray) {
			final String tag = item.split(DOUBLE_EQUAL)[0];
			final String value = item.split(DOUBLE_EQUAL)[1];
			LOG.debug("checkDicomFromProperties : tag=" + tag + ", value=" + value);
			try {
				Class aClass = serie.getClass();
				Field field = aClass.getDeclaredField(tag);
				field.setAccessible(true);
				String dicomValue = (String) field.get(serie);
				String wildcard = ImportUtils.wildcardToRegex(value);
				if (dicomValue != null && dicomValue.matches(wildcard)) {
					return true;
				}
			} catch (IllegalArgumentException | IllegalAccessException e) {
				LOG.error(e.getMessage());
			}
		}
		return false;
	}
	
	/**
	 * This method separates the datasets from a single serie in separate
	 * folders. 3 distinct dicom tags are essential for separating the datasets:
	 * acquisition number, echo numbers and image orientation(patient).
	 * Iterate over all images and sort images in groups with the same value
	 * for ImageOrientation, EchoNumbers and AcquisitionNumber.
	 * 
	 * This method translates as well from images to datasets and create the new structure.
	 * 
	 * @param serie
	 */
	private void separateDatasetsInSerie(final File serieIDFolderFile, final Serie serie) {
		final HashMap<SerieToDatasetsSeparator, Dataset>
			datasetMap = new HashMap<SerieToDatasetsSeparator, Dataset>();
		List<Image> images = serie.getImages();
		for (Iterator iterator = images.iterator(); iterator.hasNext();) {
			Image image = (Image) iterator.next();
			final int acquisitionNumber = image.getAcquisitionNumber();
			int[] echoNumbersIntArray = convertIntegers(image.getEchoNumbers());
			double[] imageOrientationPatientsDoubleArray = convertDoubles(image.getImageOrientationPatient());
			SerieToDatasetsSeparator seriesToDatasetsSeparator =
					new SerieToDatasetsSeparator(acquisitionNumber, echoNumbersIntArray, imageOrientationPatientsDoubleArray);
			boolean found = false;
			for (SerieToDatasetsSeparator seriesToDatasetsComparatorIterate : datasetMap.keySet()) {
				if (seriesToDatasetsComparatorIterate.equals(seriesToDatasetsSeparator)) {
					found = true;
					seriesToDatasetsSeparator = seriesToDatasetsComparatorIterate;
					break;
				}
			}
			// existing dataset has been found, just add the image/datasetFile
			if (found) {
				DatasetFile datasetFile = createDatasetFile(image);
				datasetMap.get(seriesToDatasetsSeparator).getExpressionFormats().get(0).getDatasetFiles().add(datasetFile);
			// new dataset has to be created, new expression format and add image/datasetfile
			} else {
				Dataset dataset = new Dataset();
				ExpressionFormat expressionFormat = new ExpressionFormat();
				expressionFormat.setType("dcm");
				dataset.getExpressionFormats().add(expressionFormat);
				DatasetFile datasetFile = createDatasetFile(image);
				expressionFormat.getDatasetFiles().add(datasetFile);
				datasetMap.put(seriesToDatasetsSeparator, dataset);
			}
		}
		
		boolean success = true;
		// create a separate folder for each group of images
		int index = 0;
		for (final SerieToDatasetsSeparator datasets : datasetMap.keySet()) {
			// create a folder
			final File folder = new File(serieIDFolderFile.getAbsolutePath() + File.separator + DATASET + index);
			success = folder.mkdirs();
			if (!success) {
				LOG.error("deleteFolder : the creation of " + folder + " failed");
			}
			// move the files into the folder
			for (final DatasetFile datasetFile : datasetMap.get(datasets).getExpressionFormats().get(0).getDatasetFiles()) {
				String path = datasetFile.getPath();
				final File oldFile = new File(path);
				if (oldFile.exists()) {
					final File newFile = new File(folder, oldFile.getName());
					success = oldFile.renameTo(newFile);
					datasetFile.setPath(newFile.getAbsolutePath());
					if (!success) {
						LOG.error("deleteFolder : moving of " + oldFile + " failed");
					}					
				}
			}
			index++;
		}
		if (!success) {
			LOG.error("Error while converting to nifti in separateDatasetsInSerie.");
		}
	}

	/**
	 * @param image
	 * @return
	 */
	private DatasetFile createDatasetFile(Image image) {
		DatasetFile datasetFile = new DatasetFile();
		datasetFile.setPath(image.getPath());
		datasetFile.setAcquisitionNumber(image.getAcquisitionNumber());
		datasetFile.setEchoNumbers(image.getEchoNumbers());
		datasetFile.setImageOrientationPatient(image.getImageOrientationPatient());
		return datasetFile;
	}
	
	/**
	 * @param seriesFolderFile
	 * @param serie
	 * @throws RestServiceException
	 */
	private File createSerieIDFolder(File seriesFolderFile, Serie serie) throws RestServiceException {
		String serieID = serie.getSeriesInstanceUID();
		File serieIDFolderFile = new File(seriesFolderFile.getAbsolutePath() + File.separator + serieID);
		if(!serieIDFolderFile.exists()) {
			serieIDFolderFile.mkdirs();
		} else {
			throw new RestServiceException(
					new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Error while creating serie id folder: folder already exists.", null));
		}
		List<Image> images = serie.getImages();
		moveFiles(serieIDFolderFile, images);
//		List<Object> nonImages = serie.getNonImages();
//		moveFiles(serieIDFolderFile, nonImages);
		return serieIDFolderFile;
	}

	/**
	 * @param serieIDFolderFile
	 * @param images
	 * @throws RestServiceException
	 */
	private void moveFiles(File serieIDFolderFile, List<Image> images) throws RestServiceException {
		for (Iterator iterator = images.iterator(); iterator.hasNext();) {
			Image image = (Image) iterator.next();
			// the path has been set in processDicomFile in DicomFileAnalyzer before
			String filePath = image.getPath();
			File oldFile = new File(filePath);
			if (oldFile.exists()) {
				File newFile = new File(serieIDFolderFile.getAbsolutePath() + File.separator + oldFile.getName());
				oldFile.renameTo(newFile);
				image.setPath(newFile.getAbsolutePath());
			} else {
				throw new RestServiceException(
						new ErrorModel(HttpStatus.UNPROCESSABLE_ENTITY.value(), "Error while creating serie id folder: file to copy does not exist.", null));
			}
		}
	}
	
	public static int[] convertIntegers(List<Integer> integers) {
		int[] ret = new int[integers.size()];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = integers.get(i).intValue();
		}
		return ret;
	}
	
	public static double[] convertDoubles(List<Double> doubles) {
		double[] ret = new double[doubles.size()];
		for (int i = 0; i < ret.length; i++) {
			ret[i] = doubles.get(i).doubleValue();
		}
		return ret;
	}

	/**
	 * Make a diff to know which files from destinationFolder are not in the
	 * given list of files.
	 *
	 * @param existingFiles
	 *            the existing files
	 * @param destinationFolder
	 *            the destination folder
	 *
	 * @return the list< file>
	 */
	private List<File> diff(final List<File> existingFiles, final String destinationFolder) {
		final List<File> resultList = new ArrayList<File>();
		final List<File> outputFiles = Arrays.asList(new File(destinationFolder).listFiles());
		for (final File file : outputFiles) {
			if (!existingFiles.contains(file)) {
				resultList.add(file);
			}
		}
		return resultList;
	}

	/**
	 * Check if the newly created nifti files list contains a .prop file
	 * If it is the case, then there has been a problem during conversion
	 * and should be considered as failed.
	 * 
	 */
	private boolean containsPropFile(List<File> niftiFiles){
		for(File current : niftiFiles){
			if(current.getPath().contains(".prop")) return true;
		}
		return false;
	}
	
}
