package org.jboss.examples.ticketmonster.rest;

import java.io.File;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;

import org.jboss.examples.ticketmonster.model.MediaItem;
import org.jboss.examples.ticketmonster.service.MediaManager;

@Path("/media")
/**
 * <p>
 *     This is a stateless service, we declare it as an EJB for transaction demarcation
 * </p>
 */
public class MediaService {
    
    @Inject
    private MediaManager mediaManager;
    
    @PersistenceContext(unitName = "primary")
    EntityManager entityManager;

    @GET
    @Path("/cache/{cachedFileName:\\S*}")
    @Produces("*/*")
    public File getCachedMediaContent(@PathParam("cachedFileName") String cachedFileName) {
        return mediaManager.getCachedFile(cachedFileName);
    }

    @GET
    @Path("/{id:\\d*}")
    @Produces("*/*")
    public File getMediaContent(@PathParam("id") Long id) {
        return mediaManager.getCachedFile(mediaManager.getPath(entityManager.find(MediaItem.class, id)).getUrl());
    }
}
