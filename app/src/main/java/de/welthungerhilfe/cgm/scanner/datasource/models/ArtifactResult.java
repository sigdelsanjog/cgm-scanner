/*
 * Child Growth Monitor - quick and accurate data on malnutrition
 * Copyright (c) 2018 Markus Matiaschek <mmatiaschek@gmail.com>
 * Copyright (c) 2018 Welthungerhilfe Innovation
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.welthungerhilfe.cgm.scanner.datasource.models;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.PrimaryKey;
import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.Locale;

import de.welthungerhilfe.cgm.scanner.datasource.repository.CsvExportableModel;

import static androidx.room.ForeignKey.CASCADE;
import static de.welthungerhilfe.cgm.scanner.datasource.database.CgmDatabase.TABLE_ARTIFACT_RESULT;

@Entity(tableName = TABLE_ARTIFACT_RESULT)
public class ArtifactResult extends CsvExportableModel implements Serializable {
    private String type;
    private int key;
    private double real;
    private String confidence_value;
    private String misc;

    @NonNull
    @ForeignKey(entity = Measure.class, parentColumns = "id", childColumns = "measure_id", onDelete = CASCADE, onUpdate = CASCADE)
    private String measure_id;

    @PrimaryKey
    @NonNull
    private String artifact_id;

    @NonNull
    public String getType() {
        return type;
    }

    public void setType(@NonNull String type) {
        this.type = type;
    }

    public int getKey() {
        return key;
    }

    public void setKey(int key) {
        this.key = key;
    }

    public double getReal() {
        return real;
    }

    public void setReal(double real) {
        this.real = real;
    }

    public String getMisc() {
        return misc;
    }

    public void setMisc(String misc) {
        this.misc = misc;
    }

    @NonNull
    public String getArtifact_id() {
        return artifact_id;
    }

    public void setArtifact_id(@NonNull String artifact_id) {
        this.artifact_id = artifact_id;
    }

    public String getConfidence_value() {
        return confidence_value;
    }

    public void setConfidence_value(String confidence_value) {
        this.confidence_value = confidence_value;
    }

    @NonNull
    public String getMeasure_id() {
        return measure_id;
    }

    public void setMeasure_id(@NonNull String measure_id) {
        this.measure_id = measure_id;
    }

    public String getCsvFormattedString() {
        return String.format(Locale.US, "%s,%d,%f,%s,%s,%s,%s", type, key, real, confidence_value, misc, measure_id, artifact_id);
    }

    @Override
    public String getCsvHeaderString() {
        return "type, key, real, confidence_value, misc, measure_id, artifact_id";
    }
}