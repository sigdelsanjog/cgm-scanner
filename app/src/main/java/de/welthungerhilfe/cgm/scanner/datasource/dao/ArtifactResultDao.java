package de.welthungerhilfe.cgm.scanner.datasource.dao;

import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;

import java.util.List;

import de.welthungerhilfe.cgm.scanner.datasource.models.ArtifactResult;

import static android.arch.persistence.room.OnConflictStrategy.REPLACE;
import static de.welthungerhilfe.cgm.scanner.datasource.database.CgmDatabase.TABLE_ARTIFACT_RESULT;

@Dao
public interface ArtifactResultDao {
    @Insert(onConflict = REPLACE)
    void insertArtifact_quality(ArtifactResult artifactResuslt);

    @Query("SELECT AVG(real) FROM " + TABLE_ARTIFACT_RESULT + " WHERE measure_id=:measure_id AND `key`=:key")
    double getArtifactResult(String measure_id, int key);

    @Query("SELECT COUNT(*) FROM " + TABLE_ARTIFACT_RESULT + " WHERE measure_id=:measure_id AND `key`=:key")
    int getPointCloudCount(String measure_id, int key);

    @Query("SELECT AVG(real) FROM " + TABLE_ARTIFACT_RESULT + " WHERE measure_id=:measure_id AND `key`=:key")
    double getAveragePointCount(String measure_id, int key);
}


