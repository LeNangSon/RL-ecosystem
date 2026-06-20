package org.openjfx.app.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RelationManager {

   private static final Map<EntityType, List<EntityType>> threat = new HashMap<>();
   private static final Map<EntityType, List<EntityType>> moveAway = new HashMap<>();
   
   static{
    threat.put(EntityType.RABBIT, Arrays.asList(EntityType.WOLF, EntityType.BEAR));
    threat.put(EntityType.FISH, Arrays.asList(EntityType.BEAR));
    threat.put(EntityType.GRASS, Arrays.asList(EntityType.RABBIT, EntityType.ELEPHANT));
    threat.put(EntityType.ALGAE, Arrays.asList(EntityType.FISH));

    moveAway.put(EntityType.RABBIT, Arrays.asList(EntityType.WOLF, EntityType.BEAR, EntityType.ELEPHANT));
    moveAway.put(EntityType.FISH, Arrays.asList(EntityType.BEAR, EntityType.WOLF, EntityType.ELEPHANT, EntityType.WOLF));
    moveAway.put(EntityType.WOLF, Arrays.asList(EntityType.ELEPHANT, EntityType.BEAR));
    moveAway.put(EntityType.BEAR, Arrays.asList(EntityType.ELEPHANT));



   }
    //  bao gồm con vật lớn hơn + predator của owner 
    public static boolean isScaredOf(EntityType owner, EntityType target) {
        if (moveAway.containsKey(owner) ){
            for (EntityType animal : moveAway.get(owner)){
                if (animal == target){
                    return true;
                }
            }

        }
        return false;
        
    }

    public static boolean isPrey(EntityType subject, EntityType target) {
        if (threat.containsKey(subject) ){
            for (EntityType animal : threat.get(subject)){
                if (animal == target){
                    return true;
                }
            }

        }
        return false;
        
    }

    
}
