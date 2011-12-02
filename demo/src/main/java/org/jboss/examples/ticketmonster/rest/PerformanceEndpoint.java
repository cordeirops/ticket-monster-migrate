package org.jboss.examples.ticketmonster.rest;

import java.util.ArrayList;
import java.util.List;

import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.examples.ticketmonster.rest.dto.PerformanceDTO;
import org.jboss.examples.ticketmonster.model.Booking;
import org.jboss.examples.ticketmonster.model.Performance;
import org.jboss.examples.ticketmonster.model.SectionAllocation;
import org.jboss.examples.ticketmonster.model.Show;

/**
 * 
 */
@Stateless
@Path("/performances")
public class PerformanceEndpoint
{
   @PersistenceContext(unitName = "primary")
   private EntityManager em;

   @POST
   @Consumes("application/json")
   public Response create(PerformanceDTO dto)
   {
      Performance entity = dto.fromDTO(null, em);
      em.persist(entity);
      return Response.created(UriBuilder.fromResource(PerformanceEndpoint.class).path(String.valueOf(entity.getId())).build()).build();
   }

   @DELETE
   @Path("/{id:[0-9][0-9]*}")
   public Response deleteById(@PathParam("id") Long id)
   {
      Performance entity = em.find(Performance.class, id);
      if (entity == null)
      {
         return Response.status(Status.NOT_FOUND).build();
      }
      Show show = entity.getShow();
      show.getPerformances().remove(entity);
      entity.setShow(null);
      this.em.merge(show);
      List<SectionAllocation> sectionAllocations = findSectionAllocationsByPerformance(entity);
      for(SectionAllocation sectionAllocation: sectionAllocations) {
         this.em.remove(sectionAllocation);
      }
      List<Booking> bookings = findBookingsByPerformance(entity);
      for(Booking booking: bookings) {
         this.em.remove(booking);
      }
      em.remove(entity);
      return Response.noContent().build();
   }

   @GET
   @Path("/{id:[0-9][0-9]*}")
   @Produces("application/json")
   public Response findById(@PathParam("id") Long id)
   {
      TypedQuery<Performance> findByIdQuery = em.createQuery("SELECT DISTINCT p FROM Performance p LEFT JOIN FETCH p.show WHERE p.id = :entityId ORDER BY p.id", Performance.class);
      findByIdQuery.setParameter("entityId", id);
      Performance entity;
      try
      {
         entity = findByIdQuery.getSingleResult();
      }
      catch (NoResultException nre)
      {
         entity = null;
      }
      if (entity == null)
      {
         return Response.status(Status.NOT_FOUND).build();
      }
      PerformanceDTO dto = new PerformanceDTO(entity);
      return Response.ok(dto).build();
   }

   @GET
   @Produces("application/json")
   public List<PerformanceDTO> listAll(@QueryParam("start") Integer startPosition, @QueryParam("max") Integer maxResult)
   {
      TypedQuery<Performance> findAllQuery = em.createQuery("SELECT DISTINCT p FROM Performance p LEFT JOIN FETCH p.show ORDER BY p.id", Performance.class);
      if (startPosition != null)
      {
         findAllQuery.setFirstResult(startPosition);
      }
      if (maxResult != null)
      {
         findAllQuery.setMaxResults(maxResult);
      }
      final List<Performance> searchResults = findAllQuery.getResultList();
      final List<PerformanceDTO> results = new ArrayList<PerformanceDTO>();
      for (Performance searchResult : searchResults)
      {
         PerformanceDTO dto = new PerformanceDTO(searchResult);
         results.add(dto);
      }
      return results;
   }

   @PUT
   @Path("/{id:[0-9][0-9]*}")
   @Consumes("application/json")
   public Response update(@PathParam("id") Long id, PerformanceDTO dto)
   {
      TypedQuery<Performance> findByIdQuery = em.createQuery("SELECT DISTINCT p FROM Performance p LEFT JOIN FETCH p.show WHERE p.id = :entityId ORDER BY p.id", Performance.class);
      findByIdQuery.setParameter("entityId", id);
      Performance entity;
      try
      {
         entity = findByIdQuery.getSingleResult();
      }
      catch (NoResultException nre)
      {
         entity = null;
      }
      entity = dto.fromDTO(entity, em);
      try
      {
         entity = em.merge(entity);
      }
      catch (OptimisticLockException e)
      {
         return Response.status(Response.Status.CONFLICT).entity(e.getEntity()).build();
      }
      return Response.noContent().build();
   }

   public List<SectionAllocation> findSectionAllocationsByPerformance(Performance performance)
   {
      CriteriaQuery<SectionAllocation> criteria = this.em
            .getCriteriaBuilder().createQuery(SectionAllocation.class);
      Root<SectionAllocation> from = criteria.from(SectionAllocation.class);
      CriteriaBuilder builder = this.em.getCriteriaBuilder();
      Predicate performanceIsSame = builder.equal(from.get("performance"), performance);
      return this.em.createQuery(
            criteria.select(from).where(performanceIsSame)).getResultList();
   }

   public List<Booking> findBookingsByPerformance(Performance performance)
   {
      CriteriaQuery<Booking> criteria = this.em
            .getCriteriaBuilder().createQuery(Booking.class);
      Root<Booking> from = criteria.from(Booking.class);
      CriteriaBuilder builder = this.em.getCriteriaBuilder();
      Predicate performanceIsSame = builder.equal(from.get("performance"), performance);
      return this.em.createQuery(
            criteria.select(from).where(performanceIsSame)).getResultList();
   }

}
