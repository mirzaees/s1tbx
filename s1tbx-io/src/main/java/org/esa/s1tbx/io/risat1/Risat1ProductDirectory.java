/*
 * Copyright (C) 2019 Skywatch. https://www.skywatch.co
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.s1tbx.io.risat1;

import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageReader;
import org.esa.s1tbx.commons.io.ImageIOFile;
import org.esa.s1tbx.commons.io.PropertyMapProductDirectory;
import org.esa.s1tbx.commons.io.SARReader;
import org.esa.s1tbx.io.ceos.risat.RisatCeosProductReader;
import org.esa.s1tbx.io.ceos.risat.RisatCeosProductReaderPlugIn;
import org.esa.snap.core.dataio.ProductReader;
import org.esa.snap.core.datamodel.*;
import org.esa.snap.core.image.ImageManager;
import org.esa.snap.core.util.ProductUtils;
import org.esa.snap.core.util.SystemUtils;
import org.esa.snap.dataio.geotiff.GeoTiffProductReaderPlugIn;
import org.esa.snap.engine_utilities.datamodel.AbstractMetadata;
import org.esa.snap.engine_utilities.datamodel.Unit;
import org.esa.snap.engine_utilities.eo.Constants;
import org.esa.snap.engine_utilities.gpf.OperatorUtils;
import org.esa.snap.engine_utilities.gpf.ReaderUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.*;
import java.util.List;

/**
 * This class represents a product directory.
 */
public class Risat1ProductDirectory extends PropertyMapProductDirectory {

    private String productName = "Risat1";
    private String productType = "Risat1";
    private final String productDescription = "";
    private boolean compactPolMode = false;

    private final DateFormat standardDateFormat = ProductData.UTC.createDateFormat("dd-MMM-yyyy HH:mm:ss");

    private static final boolean flipToSARGeometry = System.getProperty(SystemUtils.getApplicationContextId() +
            ".flip.to.sar.geometry", "false").equals("true");

    private static final GeoTiffProductReaderPlugIn geoTiffPlugIn = new GeoTiffProductReaderPlugIn();
    private static final RisatCeosProductReaderPlugIn ceosPlugIn = new RisatCeosProductReaderPlugIn();
    private List<Product> bandProductList = new ArrayList<>();

    public Risat1ProductDirectory(final File headerFile) {
        super(headerFile);
    }

    protected String getHeaderFileName() {
        return Risat1Constants.BAND_HEADER_NAME;
    }

    @Override
    protected void findImages(final MetadataElement newRoot) throws IOException {
        final String parentPath = getRelativePathToImageFolder();
        findImages(parentPath + "\\scene_HH\\", newRoot);
        findImages(parentPath + "\\scene_HV\\", newRoot);
        findImages(parentPath + "\\scene_VV\\", newRoot);
        findImages(parentPath + "\\scene_VH\\", newRoot);
        findImages(parentPath + "\\scene_RH\\", newRoot);
        findImages(parentPath + "\\scene_RV\\", newRoot);
    }

    protected void addImageFile(final String imgPath, final MetadataElement newRoot) throws IOException {
        final String name = getBandFileNameFromImage(imgPath);
        if (((name.endsWith("tif") || name.endsWith("tiff"))) && name.contains("imagery")) {
            final InputStream inStream = getInputStream(imgPath);
            if (inStream.available() > 0) {
                final ImageInputStream imgStream = ImageIOFile.createImageInputStream(inStream, getBandDimensions(newRoot, name));
                if (imgStream == null)
                    throw new IOException("Unable to open " + imgPath);

                if (!isCompressed()) {
                    final ProductReader geoTiffReader = geoTiffPlugIn.createReaderInstance();
                    Product bProduct = geoTiffReader.readProductNodes(new File(getBaseDir(), imgPath), null);
                    bandProductList.add(bProduct);
                }

                final ImageIOFile img;
                if (isSLC()) {
                    img = new ImageIOFile(name, imgStream, getTiffIIOReader(imgStream),
                            1, 2, ProductData.TYPE_INT32, productInputFile);
                } else {
                    img = new ImageIOFile(name, imgStream, getTiffIIOReader(imgStream), productInputFile);
                }
                bandImageFileMap.put(img.getName(), img);
            }
        } else if (name.endsWith(".001") && name.contains("vdf_")) {
            final ProductReader ceosReader = ceosPlugIn.createReaderInstance();
            Product bProduct = ceosReader.readProductNodes(new File(getBaseDir(), imgPath), null);
            int idx = imgPath.indexOf("scene_") + 6;
            String pol = imgPath.substring(idx, idx + 2);
            bProduct.setName(bProduct.getName() + "_" + pol);
            bandProductList.add(bProduct);
        }
    }

    public static ImageReader getTiffIIOReader(final ImageInputStream stream) throws IOException {
        ImageReader reader = null;
        final Iterator<ImageReader> imageReaders = ImageIO.getImageReaders(stream);
        while (imageReaders.hasNext()) {
            final ImageReader iioReader = imageReaders.next();
            if (iioReader instanceof TIFFImageReader) {
                reader = iioReader;
                break;
            }
        }
        if (reader == null)
            throw new IOException("Unable to open " + stream.toString());
        reader.setInput(stream, true, true);
        return reader;
    }

    private String getPol(String imgName) {
        imgName = imgName.toUpperCase();
        if (imgName.contains("RH")) {
            compactPolMode = true;
            return "RCH";
        } else if (imgName.contains("RV")) {
            compactPolMode = true;
            return "RCV";
        } else if (imgName.contains("HH")) {
            return "HH";
        } else if (imgName.contains("HV")) {
            return "HV";
        } else if (imgName.contains("VV")) {
            return "VV";
        } else if (imgName.contains("VH")) {
            return "VH";
        }
        return null;
    }

    @Override

    protected void addBands(final Product product) {

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);

        final int width, height;
        if (!bandProductList.isEmpty()) {
            final Product bandProduct = bandProductList.get(0);
            width = bandProduct.getSceneRasterWidth();
            height = bandProduct.getSceneRasterHeight();

            if (bandImageFileMap.isEmpty()) {
                if (isSLC()) {
                    for (Product bProduct : bandProductList) {
                        final String pol = getPol(bProduct.getName());

                        Band iBand = bProduct.getBandAt(0);
                        Band iTrgBand = ProductUtils.copyBand(iBand.getName(), bProduct,
                                iBand.getName() + "_" + pol, product, true);
                        iTrgBand.setUnit(Unit.REAL);
                        iTrgBand.setNoDataValue(0);
                        iTrgBand.setNoDataValueUsed(true);

                        Band qBand = bProduct.getBandAt(1);
                        Band qTrgBand = ProductUtils.copyBand(qBand.getName(), bProduct,
                                qBand.getName() + "_" + pol, product, true);
                        qTrgBand.setUnit(Unit.IMAGINARY);
                        qTrgBand.setNoDataValue(0);
                        qTrgBand.setNoDataValueUsed(true);

                        ReaderUtils.createVirtualIntensityBand(product, iTrgBand, qTrgBand, '_' + pol);
                    }
                } else {
                    for (Product bProduct : bandProductList) {
                        final String pol = getPol(bProduct.getName());
                        String bandName = "Amplitude_" + pol;
                        Band trgBand = ProductUtils.copyBand(bProduct.getBandAt(0).getName(), bProduct,
                                bandName, product, true);
                        trgBand.setUnit(Unit.AMPLITUDE);
                        trgBand.setNoDataValue(0);
                        trgBand.setNoDataValueUsed(true);

                        SARReader.createVirtualIntensityBand(product, trgBand, '_' + pol);
                    }
                }
            }

            // add metadata
            if(bandProduct.getProductReader() instanceof RisatCeosProductReader) {
                MetadataElement trgElem = AbstractMetadata.getOriginalProductMetadata(product);

                for (Product bProduct : bandProductList) {
                    final String pol = getPol(bProduct.getName());
                    MetadataElement polElem = AbstractMetadata.getOriginalProductMetadata(bProduct);
                    polElem.setName(pol + "_Metadata");

                    trgElem.addElement(polElem);
                }
            }

            if (product.getSceneGeoCoding() == null && bandProduct.getSceneGeoCoding() != null &&
                    product.getSceneRasterWidth() == bandProduct.getSceneRasterWidth() &&
                    product.getSceneRasterHeight() == bandProduct.getSceneRasterHeight()) {
                bandProduct.transferGeoCodingTo(product, null);
                Dimension tileSize = bandProduct.getPreferredTileSize();
                if (tileSize == null) {
                    tileSize = ImageManager.getPreferredTileSize(bandProduct);
                }
                product.setPreferredTileSize(tileSize);
            }
        } else {
            width = absRoot.getAttributeInt(AbstractMetadata.num_samples_per_line);
            height = absRoot.getAttributeInt(AbstractMetadata.num_output_lines);
        }

        final Set<String> keys = bandImageFileMap.keySet();                           // The set of keys in the map.
        for (String key : keys) {
            final ImageIOFile img = bandImageFileMap.get(key);

            for (int i = 0; i < img.getNumImages(); ++i) {

                if (isSLC()) {
                    boolean real = false;
                    String bandName;
                    String unit;
                    Band lastRealBand = null;

                    for (int b = 0; b < img.getNumBands(); ++b) {
                        final String pol = getPol(img.getName());
                        if (real) {
                            bandName = "i_" + pol;
                            unit = Unit.REAL;
                        } else {
                            bandName = "q_" + pol;
                            unit = Unit.IMAGINARY;
                        }

                        final Band band = new Band(bandName, img.getDataType(), width, height);
                        band.setUnit(unit);

                        product.addBand(band);
                        bandMap.put(band, new ImageIOFile.BandInfo(band, img, i, b));

                        if (real) {
                            lastRealBand = band;
                        } else {
                            ReaderUtils.createVirtualIntensityBand(product, lastRealBand, band, '_' + pol);
                        }
                        real = !real;
                    }
                } else {
                    for (int b = 0; b < img.getNumBands(); ++b) {
                        final String pol = getPol(img.getName());
                        String bandName = "Amplitude_" + pol;
                        final Band band = new Band(bandName, ProductData.TYPE_UINT32, width, height);
                        band.setUnit(Unit.AMPLITUDE);
                        band.setNoDataValue(0);
                        band.setNoDataValueUsed(true);

                        product.addBand(band);
                        bandMap.put(band, new ImageIOFile.BandInfo(band, img, i, b));

                        SARReader.createVirtualIntensityBand(product, band, '_' + pol);
                    }
                }
            }
        }

        if (compactPolMode) {
            absRoot.setAttributeInt(AbstractMetadata.polsarData, 1);
            absRoot.setAttributeString(AbstractMetadata.compact_mode, "Right Circular Hybrid Mode");
        }
    }

    @Override
    protected void addAbstractedMetadataHeader(final MetadataElement root) throws IOException {

        final MetadataElement absRoot = AbstractMetadata.addAbstractedMetadataHeader(root);
        final MetadataElement origProdRoot = AbstractMetadata.addOriginalProductMetadata(root);

        final String defStr = AbstractMetadata.NO_METADATA_STRING;
        final int defInt = AbstractMetadata.NO_METADATA;

        final MetadataElement productElem = origProdRoot.getElement("ProductMetadata");

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SPH_DESCRIPTOR,
                productElem.getAttributeString("ProductType", defStr));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ACQUISITION_MODE,
                productElem.getAttributeString("ImagingMode", defStr));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.antenna_pointing,
                productElem.getAttributeString("SensorOrientation", defStr).toLowerCase());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.BEAMS,
                productElem.getAttributeString("NumberOfBeams", defStr));

//        final MetadataElement radarCenterFrequency = productElem.getElement("radarCenterFrequency");
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency,
//                                      radarCenterFrequency.getAttributeDouble("radarCenterFrequency", defInt) / Constants.oneMillion);

        final String pass = productElem.getAttributeString("Node", defStr).toUpperCase();
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PASS, pass);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ABS_ORBIT, productElem.getAttributeDouble("ImagingOrbitNo", defInt));

        productType = productElem.getAttributeString("ProductType", defStr);
        if (productType.contains("SLANT")) {
            setSLC(true);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "COMPLEX");
        } else {
            setSLC(false);
            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.SAMPLE_TYPE, "DETECTED");
        }

        final String productId = productElem.getAttributeString("productId", defStr);
        final String beamMode = productElem.getAttributeString("beamModeMnemonic", defStr);
        final String passStr;
        if (pass.equals("ASCENDING")) {
            passStr = "ASC";
        } else {
            passStr = "DSC";
        }

        ProductData.UTC startTime, stopTime;
        if (flipToSARGeometry && pass.equals("ASCENDING")) {
            stopTime = getTime(productElem, "SceneStartTime", standardDateFormat);
            startTime = getTime(productElem, "SceneEndTime", standardDateFormat);
        } else {
            startTime = getTime(productElem, "SceneStartTime", standardDateFormat);
            stopTime = getTime(productElem, "SceneEndTime", standardDateFormat);
        }

        final DateFormat dateFormat = ProductData.UTC.createDateFormat("dd-MMM-yyyy_HH.mm");
        final Date date = startTime.getAsDate();
        final String dateString = dateFormat.format(date);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT_TYPE, productType);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.radar_frequency, 5350);

        productName = getMission() + '-' + productType + '-' + beamMode + '-' + passStr + '-' + dateString + '-' + productId;
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PRODUCT, productName);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.MISSION, getMission());
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ProcessingSystemIdentifier,
                productElem.getAttributeString("processingFacility", defStr) + '-' +
                        productElem.getAttributeString("softwareVersion", defStr));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.PROC_TIME,
                ReaderUtils.getTime(productElem, "processingTime", standardDateFormat));

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.ant_elev_corr_flag,
                getFlag(productElem, "elevationPatternCorrection"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spread_comp_flag,
                getFlag(productElem, "rangeSpreadingLossCorrection"));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.srgr_flag, isSLC() ? 0 : 1);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.first_line_time, startTime);
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.last_line_time, stopTime);

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_looks,
                productElem.getAttributeDouble("RangeLooks", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_looks,
                productElem.getAttributeDouble("AzimuthLooks", defInt));
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.slant_range_to_first_pixel,
//                productElem.getElement("slantRangeNearEdge").getAttributeDouble("slantRangeNearEdge"));
//
//        // add Range and Azimuth bandwidth
//        final MetadataElement totalProcessedRangeBandwidth = productElem.getElement("totalProcessedRangeBandwidth");
//        final MetadataElement totalProcessedAzimuthBandwidth = productElem.getElement("totalProcessedAzimuthBandwidth");
//        final double rangeBW = totalProcessedRangeBandwidth.getAttributeDouble("totalProcessedRangeBandwidth"); // Hz
//        final double azimuthBW = totalProcessedAzimuthBandwidth.getAttributeDouble("totalProcessedAzimuthBandwidth"); // Hz
//
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_bandwidth, rangeBW / Constants.oneMillion);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_bandwidth, azimuthBW);
//
//        verifyProductFormat(productElem);
//

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_output_lines,
                productElem.getAttributeInt("NoScans", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.num_samples_per_line,
                productElem.getAttributeInt("NoPixels", defInt));
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.line_time_interval,
//                                      ReaderUtils.getLineTimeInterval(startTime, stopTime,
//                                                                      absRoot.getAttributeInt(AbstractMetadata.num_output_lines)));
//

        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_spacing,
                productElem.getAttributeDouble("OutputPixelSpacing", defInt));
        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.azimuth_spacing,
                productElem.getAttributeDouble("OutputLineSpacing", defInt));
//
//        final MetadataElement pulseRepetitionFrequency = productElem.getElement("pulseRepetitionFrequency");
//        double prf = pulseRepetitionFrequency.getAttributeDouble("pulseRepetitionFrequency", defInt);
//        final MetadataElement adcSamplingRate = productElem.getElement("adcSamplingRate");
//        double rangeSamplingRate = adcSamplingRate.getAttributeDouble("adcSamplingRate", defInt) / Constants.oneMillion;
//
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.pulse_repetition_frequency, prf);
//        AbstractMetadata.setAttribute(absRoot, AbstractMetadata.range_sampling_rate, rangeSamplingRate);
//        AbstractMetadata.setAttribute(absRoot, "bistatic_correction_applied", 1);

//        if (geographicInformation != null) {
//            final MetadataElement referenceEllipsoidParameters = geographicInformation.getElement("referenceEllipsoidParameters");
//            if (referenceEllipsoidParameters != null) {
//                final MetadataElement geodeticTerrainHeight = referenceEllipsoidParameters.getElement("geodeticTerrainHeight");
//                if (geodeticTerrainHeight != null) {
//                    AbstractMetadata.setAttribute(absRoot, AbstractMetadata.avg_scene_height,
//                                                  geodeticTerrainHeight.getAttributeDouble("geodeticTerrainHeight", defInt));
//                }
//            }
//        }

        // polarizations
        getPolarizations(absRoot, productElem);
//
//        addOrbitStateVectors(absRoot, productElem);
//        addSRGRCoefficients(absRoot, productElem);
//        addDopplerCentroidCoefficients(absRoot, productElem);
    }

    private static ProductData.UTC getTime(final MetadataElement elem, final String tag, final DateFormat timeFormat) {
        if (elem == null)
            return AbstractMetadata.NO_METADATA_UTC;
        final String timeStr = elem.getAttributeString(tag, " ").toUpperCase().trim();
        return AbstractMetadata.parseUTC(timeStr, timeFormat);
    }

    private static int getFlag(final MetadataElement elem, String tag) {
        String valStr = elem.getAttributeString(tag, " ").toUpperCase();
        if (valStr.equals("FALSE") || valStr.equals("0"))
            return 0;
        else if (valStr.equals("TRUE") || valStr.equals("1"))
            return 1;
        return -1;
    }

    private void getPolarizations(final MetadataElement absRoot, final MetadataElement prodElem) {
        int i = 0;
        String pol = prodElem.getAttributeString("TxRxPol1", null);
        if (pol != null) {
            pol = pol.toUpperCase();
            absRoot.setAttributeString(AbstractMetadata.polarTags[i], pol);
            ++i;
        }
        pol = prodElem.getAttributeString("TxRxPol2", null);
        if (pol != null) {
            pol = pol.toUpperCase();
            absRoot.setAttributeString(AbstractMetadata.polarTags[i], pol);
            ++i;
        }
    }

    private void addOrbitStateVectors(final MetadataElement absRoot, final MetadataElement orbitInformation) {
        final MetadataElement orbitVectorListElem = absRoot.getElement(AbstractMetadata.orbit_state_vectors);

        final MetadataElement[] stateVectorElems = orbitInformation.getElements();
        for (int i = 1; i <= stateVectorElems.length; ++i) {
            addVector(AbstractMetadata.orbit_vector, orbitVectorListElem, stateVectorElems[i - 1], i);
        }

        // set state vector time
        if (absRoot.getAttributeUTC(AbstractMetadata.STATE_VECTOR_TIME, AbstractMetadata.NO_METADATA_UTC).
                equalElems(AbstractMetadata.NO_METADATA_UTC)) {

            AbstractMetadata.setAttribute(absRoot, AbstractMetadata.STATE_VECTOR_TIME,
                    ReaderUtils.getTime(stateVectorElems[0], "timeStamp", standardDateFormat));
        }
    }

    private void addVector(String name, MetadataElement orbitVectorListElem,
                           MetadataElement srcElem, int num) {
        final MetadataElement orbitVectorElem = new MetadataElement(name + num);

        orbitVectorElem.setAttributeUTC(AbstractMetadata.orbit_vector_time,
                ReaderUtils.getTime(srcElem, "timeStamp", standardDateFormat));

        final MetadataElement xpos = srcElem.getElement("xPosition");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_pos,
                xpos.getAttributeDouble("xPosition", 0));
        final MetadataElement ypos = srcElem.getElement("yPosition");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_pos,
                ypos.getAttributeDouble("yPosition", 0));
        final MetadataElement zpos = srcElem.getElement("zPosition");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_pos,
                zpos.getAttributeDouble("zPosition", 0));
        final MetadataElement xvel = srcElem.getElement("xVelocity");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_x_vel,
                xvel.getAttributeDouble("xVelocity", 0));
        final MetadataElement yvel = srcElem.getElement("yVelocity");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_y_vel,
                yvel.getAttributeDouble("yVelocity", 0));
        final MetadataElement zvel = srcElem.getElement("zVelocity");
        orbitVectorElem.setAttributeDouble(AbstractMetadata.orbit_vector_z_vel,
                zvel.getAttributeDouble("zVelocity", 0));

        orbitVectorListElem.addElement(orbitVectorElem);
    }

    private void addSRGRCoefficients(final MetadataElement absRoot, final MetadataElement imageGenerationParameters) {
        final MetadataElement srgrCoefficientsElem = absRoot.getElement(AbstractMetadata.srgr_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : imageGenerationParameters.getElements()) {
            if (elem.getName().equalsIgnoreCase("slantRangeToGroundRange")) {
                final MetadataElement srgrListElem = new MetadataElement(AbstractMetadata.srgr_coef_list + '.' + listCnt);
                srgrCoefficientsElem.addElement(srgrListElem);
                ++listCnt;

                final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "zeroDopplerAzimuthTime", standardDateFormat);
                srgrListElem.setAttributeUTC(AbstractMetadata.srgr_coef_time, utcTime);

                final double grOrigin = elem.getElement("groundRangeOrigin").getAttributeDouble("groundRangeOrigin", 0);
                AbstractMetadata.addAbstractedAttribute(srgrListElem, AbstractMetadata.ground_range_origin,
                        ProductData.TYPE_FLOAT64, "m", "Ground Range Origin");
                AbstractMetadata.setAttribute(srgrListElem, AbstractMetadata.ground_range_origin, grOrigin);

                final String coeffStr = elem.getAttributeString("groundToSlantRangeCoefficients", "");
                if (!coeffStr.isEmpty()) {
                    final StringTokenizer st = new StringTokenizer(coeffStr);
                    int cnt = 1;
                    while (st.hasMoreTokens()) {
                        final double coefValue = Double.parseDouble(st.nextToken());

                        final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                        srgrListElem.addElement(coefElem);
                        ++cnt;
                        AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.srgr_coef,
                                ProductData.TYPE_FLOAT64, "", "SRGR Coefficient");
                        AbstractMetadata.setAttribute(coefElem, AbstractMetadata.srgr_coef, coefValue);
                    }
                }
            }
        }
    }

    private void addDopplerCentroidCoefficients(
            final MetadataElement absRoot, final MetadataElement imageGenerationParameters) {

        final MetadataElement dopplerCentroidCoefficientsElem = absRoot.getElement(AbstractMetadata.dop_coefficients);

        int listCnt = 1;
        for (MetadataElement elem : imageGenerationParameters.getElements()) {
            if (elem.getName().equalsIgnoreCase("dopplerCentroid")) {
                final MetadataElement dopplerListElem = new MetadataElement(AbstractMetadata.dop_coef_list + '.' + listCnt);
                dopplerCentroidCoefficientsElem.addElement(dopplerListElem);
                ++listCnt;

                final ProductData.UTC utcTime = ReaderUtils.getTime(elem, "timeOfDopplerCentroidEstimate", standardDateFormat);
                dopplerListElem.setAttributeUTC(AbstractMetadata.dop_coef_time, utcTime);

                final double refTime = elem.getElement("dopplerCentroidReferenceTime").
                        getAttributeDouble("dopplerCentroidReferenceTime", 0) * 1e9; // s to ns
                AbstractMetadata.addAbstractedAttribute(dopplerListElem, AbstractMetadata.slant_range_time,
                        ProductData.TYPE_FLOAT64, "ns", "Slant Range Time");
                AbstractMetadata.setAttribute(dopplerListElem, AbstractMetadata.slant_range_time, refTime);

                final String coeffStr = elem.getAttributeString("dopplerCentroidCoefficients", "");
                if (!coeffStr.isEmpty()) {
                    final StringTokenizer st = new StringTokenizer(coeffStr);
                    int cnt = 1;
                    while (st.hasMoreTokens()) {
                        final double coefValue = Double.parseDouble(st.nextToken());

                        final MetadataElement coefElem = new MetadataElement(AbstractMetadata.coefficient + '.' + cnt);
                        dopplerListElem.addElement(coefElem);
                        ++cnt;
                        AbstractMetadata.addAbstractedAttribute(coefElem, AbstractMetadata.dop_coef,
                                ProductData.TYPE_FLOAT64, "", "Doppler Centroid Coefficient");
                        AbstractMetadata.setAttribute(coefElem, AbstractMetadata.dop_coef, coefValue);
                    }
                }
            }
        }
    }

    @Override
    protected void addGeoCoding(final Product product) {
    }

    @Override
    protected void addTiePointGrids(final Product product) {

//        final int sourceImageWidth = product.getSceneRasterWidth();
//        final int sourceImageHeight = product.getSceneRasterHeight();
//        final int gridWidth = 11;
//        final int gridHeight = 11;
//        final int subSamplingX = (int) ((float) sourceImageWidth / (float) (gridWidth - 1));
//        final int subSamplingY = (int) ((float) sourceImageHeight / (float) (gridHeight - 1));
//
//        double a = Constants.semiMajorAxis; // WGS 84: equatorial Earth radius in m
//        double b = Constants.semiMinorAxis; // WGS 84: polar Earth radius in m
//
//        // get slant range to first pixel and pixel spacing
//        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
//        final double slantRangeToFirstPixel = absRoot.getAttributeDouble(AbstractMetadata.slant_range_to_first_pixel, 0); // in m
//        final double rangeSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0); // in m
//        final boolean srgrFlag = absRoot.getAttributeInt(AbstractMetadata.srgr_flag) != 0;
//        final boolean isDescending = absRoot.getAttributeString(AbstractMetadata.PASS).equals("DESCENDING");
//        final boolean isAntennaPointingRight = absRoot.getAttributeString(AbstractMetadata.antenna_pointing).equals("right");
//
//        // get scene center latitude
//        final GeoPos sceneCenterPos =
//                product.getSceneGeoCoding().getGeoPos(new PixelPos(sourceImageWidth / 2.0f, sourceImageHeight / 2.0f), null);
//        double sceneCenterLatitude = sceneCenterPos.lat; // in deg
//
//        // get near range incidence angle
//        final MetadataElement origProdRoot = AbstractMetadata.getOriginalProductMetadata(product);
//        final MetadataElement productElem = origProdRoot.getElement("product");
//        final MetadataElement imageGenerationParameters = productElem.getElement("imageGenerationParameters");
//        final MetadataElement sarProcessingInformation = imageGenerationParameters.getElement("sarProcessingInformation");
//        final MetadataElement incidenceAngleNearRangeElem = sarProcessingInformation.getElement("incidenceAngleNearRange");
//        final double nearRangeIncidenceAngle = (float) incidenceAngleNearRangeElem.getAttributeDouble("incidenceAngleNearRange", 0);
//
//        final double alpha1 = nearRangeIncidenceAngle * Constants.DTOR;
//        final double lambda = sceneCenterLatitude * Constants.DTOR;
//        final double cos2 = FastMath.cos(lambda) * FastMath.cos(lambda);
//        final double sin2 = FastMath.sin(lambda) * FastMath.sin(lambda);
//        final double e2 = (b * b) / (a * a);
//        final double rt = a * Math.sqrt((cos2 + e2 * e2 * sin2) / (cos2 + e2 * sin2));
//        final double rt2 = rt * rt;
//
//        double groundRangeSpacing;
//        if (srgrFlag) { // detected
//            groundRangeSpacing = rangeSpacing;
//        } else {
//            groundRangeSpacing = rangeSpacing / FastMath.sin(alpha1);
//        }
//
//        double deltaPsi = groundRangeSpacing / rt; // in radian
//        final double r1 = slantRangeToFirstPixel;
//        final double rtPlusH = Math.sqrt(rt2 + r1 * r1 + 2.0 * rt * r1 * FastMath.cos(alpha1));
//        final double rtPlusH2 = rtPlusH * rtPlusH;
//        final double theta1 = FastMath.acos((r1 + rt * FastMath.cos(alpha1)) / rtPlusH);
//        final double psi1 = alpha1 - theta1;
//        double psi = psi1;
//        float[] incidenceAngles = new float[gridWidth];
//        final int n = gridWidth * subSamplingX;
//        int k = 0;
//        for (int i = 0; i < n; i++) {
//            final double ri = Math.sqrt(rt2 + rtPlusH2 - 2.0 * rt * rtPlusH * FastMath.cos(psi));
//            final double alpha = FastMath.acos((rtPlusH2 - ri * ri - rt2) / (2.0 * ri * rt));
//            if (i % subSamplingX == 0) {
//                int index = k++;
//
//                if (!flipToSARGeometry && (isDescending && isAntennaPointingRight || (!isDescending && !isAntennaPointingRight))) {// flip
//                    index = gridWidth - 1 - index;
//                }
//
//                incidenceAngles[index] = (float) (alpha * Constants.RTOD);
//            }
//
//            if (!srgrFlag) { // complex
//                groundRangeSpacing = rangeSpacing / FastMath.sin(alpha);
//                deltaPsi = groundRangeSpacing / rt;
//            }
//            psi = psi + deltaPsi;
//        }
//
//        float[] incidenceAngleList = new float[gridWidth * gridHeight];
//        for (int j = 0; j < gridHeight; j++) {
//            System.arraycopy(incidenceAngles, 0, incidenceAngleList, j * gridWidth, gridWidth);
//        }
//
//        final TiePointGrid incidentAngleGrid = new TiePointGrid(
//                OperatorUtils.TPG_INCIDENT_ANGLE, gridWidth, gridHeight, 0, 0,
//                subSamplingX, subSamplingY, incidenceAngleList);
//
//        incidentAngleGrid.setUnit(Unit.DEGREES);
//
//        product.addTiePointGrid(incidentAngleGrid);
//
//        addSlantRangeTime(product, imageGenerationParameters);
    }

    private void addSlantRangeTime(final Product product, final MetadataElement imageGenerationParameters) {

        class coefList {
            double utcSeconds = 0.0;
            double grOrigin = 0.0;
            final List<Double> coefficients = new ArrayList<>();
        }

        final List<coefList> segmentsArray = new ArrayList<>();

        for (MetadataElement elem : imageGenerationParameters.getElements()) {
            if (elem.getName().equalsIgnoreCase("slantRangeToGroundRange")) {
                final coefList coef = new coefList();
                segmentsArray.add(coef);
                coef.utcSeconds = ReaderUtils.getTime(elem, "zeroDopplerAzimuthTime", standardDateFormat).getMJD() * 24 * 3600;
                coef.grOrigin = elem.getElement("groundRangeOrigin").getAttributeDouble("groundRangeOrigin", 0);

                final String coeffStr = elem.getAttributeString("groundToSlantRangeCoefficients", "");
                if (!coeffStr.isEmpty()) {
                    final StringTokenizer st = new StringTokenizer(coeffStr);
                    while (st.hasMoreTokens()) {
                        coef.coefficients.add(Double.parseDouble(st.nextToken()));
                    }
                }
            }
        }

        final MetadataElement absRoot = AbstractMetadata.getAbstractedMetadata(product);
        final double lineTimeInterval = absRoot.getAttributeDouble(AbstractMetadata.line_time_interval, 0);
        final ProductData.UTC startTime = absRoot.getAttributeUTC(AbstractMetadata.first_line_time, AbstractMetadata.NO_METADATA_UTC);
        final double startSeconds = startTime.getMJD() * 24 * 3600;
        final double pixelSpacing = absRoot.getAttributeDouble(AbstractMetadata.range_spacing, 0);
        final boolean isDescending = absRoot.getAttributeString(AbstractMetadata.PASS).equals("DESCENDING");
        final boolean isAntennaPointingRight = absRoot.getAttributeString(AbstractMetadata.antenna_pointing).equals("right");

        final int gridWidth = 11;
        final int gridHeight = 11;
        final int sceneWidth = product.getSceneRasterWidth();
        final int sceneHeight = product.getSceneRasterHeight();
        final int subSamplingX = sceneWidth / (gridWidth - 1);
        final int subSamplingY = sceneHeight / (gridHeight - 1);
        final float[] rangeDist = new float[gridWidth * gridHeight];
        final float[] rangeTime = new float[gridWidth * gridHeight];

        final coefList[] segments = segmentsArray.toArray(new coefList[segmentsArray.size()]);

        int k = 0;
        int c = 0;
        for (int j = 0; j < gridHeight; j++) {
            final double time = startSeconds + (j * lineTimeInterval);
            while (c < segments.length && segments[c].utcSeconds < time)
                ++c;
            if (c >= segments.length)
                c = segments.length - 1;

            final coefList coef = segments[c];
            final double GR0 = coef.grOrigin;
            final double s0 = coef.coefficients.get(0);
            final double s1 = coef.coefficients.get(1);
            final double s2 = coef.coefficients.get(2);
            final double s3 = coef.coefficients.get(3);
            final double s4 = coef.coefficients.get(4);

            for (int i = 0; i < gridWidth; i++) {
                int x = i * subSamplingX;
                final double GR = x * pixelSpacing;
                final double g = GR - GR0;
                final double g2 = g * g;

                //SlantRange = s0 + s1(GR - GR0) + s2(GR-GR0)^2 + s3(GRGR0)^3 + s4(GR-GR0)^4;
                rangeDist[k++] = (float) (s0 + s1 * g + s2 * g2 + s3 * g2 * g + s4 * g2 * g2);
            }
        }

        // get slant range time in nanoseconds from range distance in meters
        for (int i = 0; i < rangeDist.length; i++) {
            int index = i;
            if (!flipToSARGeometry && (isDescending && isAntennaPointingRight || !isDescending && !isAntennaPointingRight)) // flip for descending RS2
                index = rangeDist.length - 1 - i;

            rangeTime[index] = (float) (rangeDist[i] / Constants.halfLightSpeed * Constants.oneBillion); // in ns
        }

        final TiePointGrid slantRangeGrid = new TiePointGrid(OperatorUtils.TPG_SLANT_RANGE_TIME,
                gridWidth, gridHeight, 0, 0, subSamplingX, subSamplingY, rangeTime);

        product.addTiePointGrid(slantRangeGrid);
        slantRangeGrid.setUnit(Unit.NANOSECONDS);
    }

    private static String getMission() {
        return "RISAT1";
    }

    @Override
    protected String getProductName() {
        return productName;
    }

    @Override
    protected String getProductDescription() {
        return productDescription;
    }

    @Override
    protected String getProductType() {
        return productType;
    }
}
